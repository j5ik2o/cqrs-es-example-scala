package com.github.j5ik2o.cqrs.es.example.scala.rmu

/** The Read Model Updater is delivered by issue #4. It will subscribe to the head table's DynamoDB Streams, deduplicate
  * by (aggregate_id, seq_nr), fill gaps from the journal and record positions in read_model_positions.
  */
object RmuPlaceholder
