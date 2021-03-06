// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.platform.sandbox.services.reset

import com.digitalasset.platform.sandbox.SandboxBackend
import com.digitalasset.platform.sandbox.services.SandboxFixture

final class ResetServiceH2DatabaseIT
    extends ResetServiceDatabaseIT
    with SandboxFixture
    with SandboxBackend.H2Database
