#!/usr/bin/env python3
import psutil
import time
import json
import socket
from confluent_kafka import Producer

# Configuration Kafka
conf = {
    'bootstrap.servers': 'localhost:9092',
    'client.id': socket.gethostname(),
    'acks': 'all' # Garantie qu'aucune métrique n'est perdue
}

producer = Producer(conf)
topic_name = "system_metrics"

def delivery_report(err, msg):
    """ Callback appelé une fois le message livré ou en cas d'échec. """
    if err is not None:
        print(f"Échec de livraison : {err}")
    else:
        # Optionnel : décommenter pour voir les envois
        print(f"Envoyé: {msg.key().decode()} -> Partition: {msg.partition()}")
        pass

try:
    # Initialisation psutil (Il faut initialiser les deux compteurs séparément)
    psutil.cpu_percent(interval=None, percpu=True)  # Pour les cœurs individuels
    psutil.cpu_percent(interval=None, percpu=False) # Pour le CPU global
    
    print(f"Démarrage du producer (acks=all) sur le topic '{topic_name}'...")
    
    while True:
        ts = time.time()
        metrics_batch = []
        
        # 1. CPU Global (Nouveau)
        metrics_batch.append({
            "timestamp": ts,
            "component_id": "CPU_TOTAL",
            "value": psutil.cpu_percent(interval=None, percpu=False),
            "unit": "%"
        })
        
        # 2. CPU par cœur
        for i, val in enumerate(psutil.cpu_percent(interval=None, percpu=True)):
            metrics_batch.append({
                "timestamp": ts,
                "component_id": f"CPU_{i}",
                "value": val,
                "unit": "%"
            })
            
        # 3. RAM
        metrics_batch.append({
            "timestamp": ts,
            "component_id": "RAM",
            "value": psutil.virtual_memory().percent,
            "unit": "%"
        })
        
        # 4. DISK
        metrics_batch.append({
            "timestamp": ts,
            "component_id": "DISK",
            "value": psutil.disk_usage('/').percent,
            "unit": "%"
        })
        
        # Envoi de chaque métrique individuellement
        for metric in metrics_batch:
            component = metric["component_id"]
            producer.produce(
                topic=topic_name,
                key=component.encode('utf-8'), # Clé de partitionnement
                value=json.dumps(metric).encode('utf-8'),
                callback=delivery_report
            )
        
        producer.poll(0)
        print(f"[{time.strftime('%H:%M:%S')}] Batch de {len(metrics_batch)} métriques envoyé.")
        time.sleep(1)
        
except KeyboardInterrupt:
    print("\nArrêt du producer...")
finally:
    producer.flush()