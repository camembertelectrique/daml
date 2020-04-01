// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.store.dao.events

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.participant.state.v1.{Offset, TransactionId}
import com.digitalasset.ledger.api.v1.transaction_service.{
  GetFlatTransactionResponse,
  GetTransactionResponse,
  GetTransactionsResponse
}
import com.digitalasset.platform.ApiOffset
import com.digitalasset.platform.store.dao.{DbDispatcher, PaginatingAsyncStream}
import com.digitalasset.platform.store.SimpleSqlAsVectorOf.SimpleSqlAsVectorOf

import scala.concurrent.{ExecutionContext, Future}

/**
  * @param dispatcher Executes the queries prepared by this object
  * @param executionContext Runs transformations on data fetched from the database
  * @param pageSize The number of events to fetch at a time the database when serving streaming calls
  * @see [[PaginatingAsyncStream]]
  */
private[dao] final class TransactionsReader(
    dispatcher: DbDispatcher,
    executionContext: ExecutionContext,
    pageSize: Int,
) {

  private def offsetFor(response: GetTransactionsResponse): Offset =
    ApiOffset.assertFromString(response.transactions.head.offset)

  def getFlatTransactions(
      startExclusive: Offset,
      endInclusive: Offset,
      filter: FilterRelation,
      verbose: Boolean,
  ): Source[(Offset, GetTransactionsResponse), NotUsed] = {
    val events =
      PaginatingAsyncStream(pageSize) { offset =>
        val query =
          EventsTable
            .preparePagedGetFlatTransactions(
              startExclusive = startExclusive,
              endInclusive = endInclusive,
              filter = filter,
              pageSize = pageSize,
              rowOffset = offset,
            )
            .withFetchSize(Some(pageSize))
        dispatcher.executeSql("get_flat_transactions") { implicit connection =>
          query.asVectorOf(EventsTable.flatEventParser(verbose = verbose))
        }
      }

    groupContiguous(events)(by = _.transactionId)
      .flatMapConcat { events =>
        val response = EventsTable.Entry.toGetTransactionsResponse(events)
        Source(response.map(r => offsetFor(r) -> r))
      }
  }

  def lookupFlatTransactionById(
      transactionId: TransactionId,
      requestingParties: Set[Party],
  ): Future[Option[GetFlatTransactionResponse]] = {
    val query = EventsTable.prepareLookupFlatTransactionById(transactionId, requestingParties)
    dispatcher
      .executeSql(
        description = "lookup_flat_transaction_by_id",
        extraLog = Some(s"tx: $transactionId, parties = ${requestingParties.mkString(", ")}"),
      ) { implicit connection =>
        query.as(EventsTable.flatEventParser(verbose = true).*)
      }
      .map(EventsTable.Entry.toGetFlatTransactionResponse)(executionContext)
  }

  def lookupTransactionTreeById(
      transactionId: TransactionId,
      requestingParties: Set[Party],
  ): Future[Option[GetTransactionResponse]] = {
    val query = EventsTable.prepareLookupTransactionTreeById(transactionId, requestingParties)
    dispatcher
      .executeSql(
        description = "lookup_transaction_tree_by_id",
        extraLog = Some(s"tx: $transactionId, parties = ${requestingParties.mkString(", ")}"),
      ) { implicit connection =>
        query.as(EventsTable.treeEventParser(verbose = true).*)
      }
      .map(EventsTable.Entry.toTransactionTree)(executionContext)
  }

}
