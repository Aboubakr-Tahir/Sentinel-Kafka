# 🛡️ Sentinel - Real-time System Monitoring

Sentinel est une solution de monitoring système temps réel basée sur **Apache Kafka**. Elle collecte des métriques système (CPU, RAM, Disque) via Python, les traite via un Consumer Scala (Kafka Streams), et archive les alertes critiques via Kafka Connect.

## Architecture

1.  **Ingestion (Python)** : Collecte les métriques système (psutil) et les envoie vers Kafka (`system_metrics`).
2.  **Processing (Scala)** : Consomme les métriques, analyse les seuils critiques (fenêtrage, détection d'anomalies) et publie des alertes (`system_alerts`).
3.  **Archivage (Kafka Connect)** : Sauvegarde les alertes critiques dans un fichier de log local via SMT (Single Message Transforms).

## Prérequis

*   **Kafka** (v3.x ou 4.x) & Zookeeper (ou KRaft)
*   **Python** 3.8+
*   **SBT** (Scala Build Tool) & **Java** 17+

## Installation & Démarrage

### 1. Démarrer l'infrastructure Kafka
Assurez-vous que Zookeeper et Kafka Broker sont lancés. Créez les topics nécessaires :
```bash
kafka-topics.sh --create --topic system_metrics --bootstrap-server localhost:9092
kafka-topics.sh --create --topic system_alerts --bootstrap-server localhost:9092
```

### 2. Configuration Python (Producer)
Installez les dépendances :
```bash
pip install -r requirements.txt
```
Lancer le producteur :
```bash
python3 src/producer.py
```

### 3. Configuration Scala (Stream Processor)
Compilez et lancez le processeur d'alertes :
```bash
sbt "runMain sentinel.AlertSystem"
```

### 4. Configuration Kafka Connect (Archivage)
Pour sauvegarder les alertes dans un fichier (`tmp/alerts.log`).

1.  Assurez-vous que le plugin `connect-file-x.x.x.jar` est dans le dossier `plugins/`.
2.  Lancer le connecteur en mode standalone :
```bash
<path-to-kafka>/bin/connect-standalone.sh config/connect-standalone.properties config/alerts-sink.properties
```

## Structure du Projet

```
Sentinel/
├── config/                  # Configurations Kafka Connect (.properties)
├── plugins/                 # Dossier pour les JARs Kafka Connect (exclus du git)
├── src/                     # Code source
│   ├── producer.py          # Script Python de collecte
│   ├── admin_create.py      # Script admin Kafka
│   └── main/scala/          # Consommateur et logique d'alerte Scala
├── tmp/                     # Dossier de destination des logs (exclus du git)
├── build.sbt                # Définition du projet Scala
└── README.md
```

