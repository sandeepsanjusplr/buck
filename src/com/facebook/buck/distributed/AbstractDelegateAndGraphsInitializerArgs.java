/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.distributed;

import com.facebook.buck.distributed.build_client.BuildSlaveTimingStatsTracker;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.io.filesystem.ProjectFilesystemFactory;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.step.ExecutorPool;
import com.facebook.buck.util.concurrent.WeightedListeningExecutorService;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionedTargetGraphCache;
import com.google.common.util.concurrent.ListeningExecutorService;
import java.util.Map;
import org.immutables.value.Value;

/** Constructor arguments for DelegateAndGraphsInitializer. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractDelegateAndGraphsInitializerArgs {
  public abstract DistBuildState getState();

  public abstract BuildSlaveTimingStatsTracker getTimingStatsTracker();

  public abstract VersionedTargetGraphCache getVersionedTargetGraphCache();

  public abstract ActionGraphCache getActionGraphCache();

  public abstract Parser getParser();

  public abstract BuckEventBus getBuckEventBus();

  public abstract int getCacheKeySeed();

  public abstract ProjectFilesystemFactory getProjectFilesystemFactory();

  public abstract WeightedListeningExecutorService getExecutorService();

  public abstract Map<ExecutorPool, ListeningExecutorService> getExecutors();

  public abstract FileContentsProvider getProvider();
}
