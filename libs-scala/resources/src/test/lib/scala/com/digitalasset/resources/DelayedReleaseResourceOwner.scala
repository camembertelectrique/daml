// Copyright (c) 2020 The DAML Authors. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.resources

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

object DelayedReleaseResourceOwner {
  def apply[T](value: T, releaseDelay: FiniteDuration)(
      implicit executionContext: ExecutionContext
  ): TestResourceOwner[T] =
    new TestResourceOwner(
      Future.successful(value),
      _ => Future(Thread.sleep(releaseDelay.toMillis))(ExecutionContext.global))
}