// Copyright (c) 2019 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.participant.state.index.v2

import com.daml.ledger.participant.state.v1.{ParticipantId, SubmissionId}
import com.digitalasset.ledger.api.domain.{PartyEntry, PartyDetails}

import scala.concurrent.Future

/**
  * Serves as a backend to implement
  * [[com.digitalasset.ledger.api.v1.admin.party_management_service.PartyManagementServiceGrpc]]
  */
trait IndexPartyManagementService {
  def getParticipantId(): Future[ParticipantId]

  def listParties(): Future[List[PartyDetails]]

  /** Try to retrieve the party allocation entry given a submission id. */
  def lookupPartyAllocationEntry(submissionId: SubmissionId): Future[Option[PartyEntry]]
}
