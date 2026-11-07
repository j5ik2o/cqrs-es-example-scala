package com.github.j5ik2o.cqrs.es.example.scala.command.interfaceAdaptor.repository

import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChat
import com.github.j5ik2o.cqrs.es.example.scala.command.domain.groupChat.GroupChatEvent
import com.github.j5ik2o.event.store.adapter.java.dynamodb.DynamoDbTableConfig
import com.github.j5ik2o.event.store.adapter.scala.EventStore
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

import java.net.URI

final case class DynamoDbSettings(
    journalTableName: String,
    snapshotTableName: String,
    headTableName: String,
    snapshotIndexName: String,
    region: String,
    endpointUrl: Option[String]
)

object EventStoreFactory {

  def memory(): EventStore[GroupChatEvent, GroupChat] =
    EventStore.ofMemory[GroupChatEvent, GroupChat](GroupChatSerializers.eventStoreConfig).get

  def dynamoDb(settings: DynamoDbSettings): EventStore[GroupChatEvent, GroupChat] = {
    val clientBuilder = DynamoDbClient.builder().region(Region.of(settings.region))
    settings.endpointUrl.foreach(endpoint => clientBuilder.endpointOverride(URI.create(endpoint)))

    val tables = DynamoDbTableConfig
      .builder()
      .journalTableName(settings.journalTableName)
      .snapshotTableName(settings.snapshotTableName)
      .headTableName(settings.headTableName)
      .snapshotAidIndexName(settings.snapshotIndexName)
      .build()

    EventStore
      .ofDynamoDB(clientBuilder.build(), tables, GroupChatSerializers.eventStoreConfig)
      .get
  }
}
