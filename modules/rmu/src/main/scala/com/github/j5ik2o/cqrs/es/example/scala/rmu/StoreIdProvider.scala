package com.github.j5ik2o.cqrs.es.example.scala.rmu

import scala.util.Try

/** Returns the store identifier shared by the three DynamoDB tables. */
trait StoreIdProvider {
  def storeId(): Try[String]
}
