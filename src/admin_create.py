from confluent_kafka.admin import AdminClient, NewTopic

def create_system_metrics_topic():
    conf = {'bootstrap.servers': 'localhost:9092'}
    admin_client = AdminClient(conf)

    topic_name = "system_metrics"
    # Configuration : 3 partitions, 1 réplica, rétention 24h
    new_topic = NewTopic(
        topic_name, 
        num_partitions=3, 
        replication_factor=1,
        config={'retention.ms': '86400000'} # 24 heures en ms
    )

    # Création du topic
    fs = admin_client.create_topics([new_topic])

    for topic, future in fs.items():
        try:
            future.result()  # Attend la confirmation
            print(f"Topic '{topic}' créé avec 3 partitions.")
        except Exception as e:
            if "Topic already exists" in str(e):
                print(f"Le topic '{topic}' existe déjà. Supprimez-le ou utilisez un autre nom pour changer les partitions.")
            else:
                print(f"Erreur : {e}")


create_system_metrics_topic()