// Copyright (c) 2020 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.api.server.damlonx.reference.v2

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.codahale.metrics.SharedMetricRegistries
import com.daml.ledger.api.server.damlonx.reference.v2.cli.Cli
import com.daml.ledger.participant.state.kvutils.InMemoryKVParticipantState
import com.daml.ledger.participant.state.v1.{ReadService, SubmissionId, WriteService}
import com.digitalasset.daml.lf.archive.DarReader
import com.digitalasset.daml.lf.data.Ref
import com.digitalasset.daml_lf_dev.DamlLf.Archive
import com.digitalasset.ledger.api.auth.{AuthService, AuthServiceWildcard}
import com.digitalasset.platform.apiserver.{ApiServerConfig, StandaloneApiServer}
import com.digitalasset.platform.common.logging.NamedLoggerFactory
import com.digitalasset.platform.indexer.{IndexerConfig, StandaloneIndexerServer}
import com.digitalasset.platform.resources.{Resource, ResourceOwner}
import org.slf4j.LoggerFactory

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}
import scala.util.Try

object ReferenceServer extends App {
  val logger = LoggerFactory.getLogger("indexed-kvutils")

  val config =
    Cli
      .parse(
        args,
        binaryName = "damlonx-reference-server",
        description = "A fully compliant DAML Ledger API server backed by an in-memory store.",
      )
      .getOrElse(sys.exit(1))

  implicit val system: ActorSystem = ActorSystem("indexed-kvutils")
  implicit val materializer: Materializer = Materializer(system)
  implicit val executionContext: ExecutionContext = system.dispatcher
  val ledgerId = Ref.LedgerString.assertFromString(
    if (config.ledgerId.isEmpty) UUID.randomUUID.toString else config.ledgerId)

  val resource = for {
    // Take ownership of the actor system and materializer so they're cleaned up properly.
    // This is necessary because we can't declare them as implicits within a `for` comprehension.
    _ <- ResourceOwner.forActorSystem(() => system).acquire()
    _ <- ResourceOwner.forMaterializer(() => materializer).acquire()
    ledger <- ResourceOwner
      .forCloseable(() =>
        new InMemoryKVParticipantState(config.participantId, ledgerId, config.timeModel))
      .acquire()
    _ = config.archiveFiles.foreach { file =>
      val submissionId = SubmissionId.assertFromString(UUID.randomUUID().toString)
      for {
        dar <- DarReader { case (_, x) => Try(Archive.parseFrom(x)) }
          .readArchiveFromFile(file)
      } yield ledger.uploadPackages(submissionId, dar.all, None)
    }
    _ <- startIndexerServer(config, readService = ledger)
    _ <- startApiServer(
      config,
      readService = ledger,
      writeService = ledger,
      authService = AuthServiceWildcard,
    )
    _ <- Resource.sequenceIgnoringValues(
      for {
        (extraParticipantId, port, jdbcUrl) <- config.extraParticipants
      } yield {
        val participantConfig = config.copy(
          port = port,
          participantId = extraParticipantId,
          jdbcUrl = jdbcUrl,
        )
        for {
          _ <- startIndexerServer(participantConfig, readService = ledger)
          _ <- startApiServer(
            participantConfig,
            readService = ledger,
            writeService = ledger,
            authService = AuthServiceWildcard,
          )
        } yield ()
      }
    )
  } yield ()

  resource.asFuture.failed.foreach { exception =>
    logger.error("Shutting down because of an initialization error.", exception)
    System.exit(1)
  }

  Runtime.getRuntime.addShutdownHook(new Thread(() => Await.result(resource.release(), 10.seconds)))

  private def startIndexerServer(config: Config, readService: ReadService): Resource[Unit] =
    new StandaloneIndexerServer(
      readService,
      IndexerConfig(config.participantId, config.jdbcUrl, config.startupMode),
      NamedLoggerFactory.forParticipant(config.participantId),
      SharedMetricRegistries.getOrCreate(s"indexer-${config.participantId}"),
    ).acquire()

  private def startApiServer(
      config: Config,
      readService: ReadService,
      writeService: WriteService,
      authService: AuthService,
  ): Resource[Unit] =
    new StandaloneApiServer(
      ApiServerConfig(
        config.participantId,
        config.archiveFiles,
        config.port,
        config.jdbcUrl,
        config.tlsConfig,
        config.timeProvider,
        config.maxInboundMessageSize,
        config.portFile,
      ),
      readService,
      writeService,
      authService,
      NamedLoggerFactory.forParticipant(config.participantId),
      SharedMetricRegistries.getOrCreate(s"ledger-api-server-${config.participantId}"),
    ).acquire()
}
