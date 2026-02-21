import streamlit as st
import pandas as pd
import time
from confluent_kafka import Consumer
from confluent_kafka.admin import AdminClient
import os
import json
from collections import deque

# Configuration de la page
st.set_page_config(
    page_title="Sentinel Dashboard",
    page_icon="🛡️",
    layout="wide",
    initial_sidebar_state="expanded"
)

# Initialisation de l'historique et des métriques de santé
if 'cpu_history' not in st.session_state:
    st.session_state.cpu_history = deque(maxlen=60)
if 'latest_metrics' not in st.session_state:
    st.session_state.latest_metrics = {"RAM": 0.0, "DISK": 0.0}
if 'last_log_pos' not in st.session_state:
    st.session_state.last_log_pos = 0
if 'alert_logs' not in st.session_state:
    st.session_state.alert_logs = deque(maxlen=15) # On garde les 15 dernières alertes
if 'msg_count' not in st.session_state:
    st.session_state.msg_count = 0
if 'start_time' not in st.session_state:
    st.session_state.start_time = time.time()

# Config Kafka
KAFKA_CONF = {
    'bootstrap.servers': 'localhost:9092',
    'group.id': 'streamlit-dashboard-group',
    'auto.offset.reset': 'latest',
    'enable.auto.commit': True
}

@st.cache_resource
def get_consumer():
    c = Consumer(KAFKA_CONF)
    c.subscribe(['system_metrics'])
    return c

@st.cache_resource
def get_admin():
    return AdminClient({'bootstrap.servers': 'localhost:9092'})

def main():
    st.title("🛡️ Sentinel - Real-time System Monitoring")

    # Barre latérale : Kafka Health
    with st.sidebar:
        st.header("🚦 Pipeline Health")
        
        # 1. État du Broker
        broker_status = st.empty()
        admin = get_admin()
        try:
            metadata = admin.list_topics(timeout=2.0)
            broker_status.success(f"Broker: Connected ({len(metadata.brokers)} nodes)")
        except Exception:
            broker_status.error("Broker: Disconnected")

        st.markdown("---")
        
        # 2. Débit (Throughput)
        throughput_metric = st.empty()
        total_msg_metric = st.empty()
        
        # 3. Détails des Topics
        st.write("**Topics Metadata**")
        topics_info = st.empty()
        if 'metadata' in locals():
            info_text = ""
            for t in ["system_metrics", "system_alerts"]:
                if t in metadata.topics:
                    partitions = len(metadata.topics[t].partitions)
                    info_text += f"- `{t}`: {partitions} partition(s)\n"
            topics_info.markdown(info_text)

    # Zone 1 : Live Telemetry
    st.subheader("📡 Live Telemetry")
    col1, col2, col3 = st.columns([2, 1, 1])

    with col1:
        st.write("**CPU Usage (60s Window)**")
        chart_placeholder = st.empty()
    
    with col2:
        ram_placeholder = st.empty()
    
    with col3:
        disk_placeholder = st.empty()

    st.markdown("---")

    # Zone 2 : Sentinel Alerts
    st.subheader("🚨 Sentinel Alerts (Detected by Scala)")
    alert_placeholder = st.empty()

    # Affichage initial
    if len(st.session_state.cpu_history) > 0:
        chart_placeholder.line_chart(list(st.session_state.cpu_history))
    ram_placeholder.metric("RAM Usage", f"{st.session_state.latest_metrics['RAM']}%")
    disk_placeholder.metric("Disk Usage", f"{st.session_state.latest_metrics['DISK']}%")

    with alert_placeholder.container():
        for alert in st.session_state.alert_logs:
            st.error(f"⚠️ {alert}")

    consumer = get_consumer()
    log_file_path = "/home/aboubakr/projects/Sentinel/tmp/alerts.log"

    # Boucle de lecture en direct
    while True:
        # PARTIE 1 : Lecture Kafka (Metrics)
        msg = consumer.poll(0.1)
        
        if msg is not None and not msg.error():
            st.session_state.msg_count += 1
            try:
                data = json.loads(msg.value().decode('utf-8'))
                comp_id = data.get('component_id', '')
                val = data.get('value', 0.0)

                if comp_id == "CPU_TOTAL":
                    st.session_state.cpu_history.append(val)
                elif comp_id == "RAM":
                    st.session_state.latest_metrics["RAM"] = val
                elif comp_id == "DISK":
                    st.session_state.latest_metrics["DISK"] = val
                
                # Mise à jour visuelle immédiate
                if len(st.session_state.cpu_history) > 0:
                    chart_placeholder.line_chart(list(st.session_state.cpu_history))
                
                ram_placeholder.metric("RAM Usage", f"{st.session_state.latest_metrics['RAM']}%")
                disk_placeholder.metric("Disk Usage", f"{st.session_state.latest_metrics['DISK']}%")

            except Exception as e:
                st.error(f"Erreur de décodage Kafka: {e}")

        # PARTIE 3 : Mise à jour Kafka Health (Stats)
        elapsed_time = time.time() - st.session_state.start_time
        msgs_per_sec = st.session_state.msg_count / elapsed_time if elapsed_time > 0 else 0
        total_msg_metric.metric("Total Messages", f"{st.session_state.msg_count}")
        throughput_metric.metric("Throughput", f"{msgs_per_sec:.1f} msg/s")

        # PARTIE 2 : Lecture Log File (Alerts Scala)
        if os.path.exists(log_file_path):
            with open(log_file_path, 'r') as f:
                # On se place à la dernière position lue
                f.seek(st.session_state.last_log_pos)
                new_lines = f.readlines()
                # On mémorise la nouvelle position
                st.session_state.last_log_pos = f.tell()

                if new_lines:
                    # On ajoute au début de l'historique (Nouveau -> Haut)
                    for line in new_lines:
                        if line.strip():
                             st.session_state.alert_logs.appendleft(line.strip())
                    
                    # On redessine le conteneur d'alertes
                    with alert_placeholder.container():
                        for alert in st.session_state.alert_logs:
                             st.error(f"⚠️ {alert}")
        
        # Un petit sleep pour ne pas saturer la boucle
        time.sleep(0.05)

if __name__ == "__main__":
    main()
