/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.direct.portable;

/**
 * Schedules and completes {@link TransformExecutor TransformExecutors}, controlling concurrency as
 * appropriate for the {@link StepAndKey} the executor exists for.
 */
interface TransformExecutorService {
  /** Schedule the provided work to be eventually executed. */
  void schedule(TransformExecutor work);

  /**
   * Finish executing the provided work. This may cause additional {@link DirectTransformExecutor
   * TransformExecutors} to be evaluated.
   */
  void complete(TransformExecutor completed);

  /**
   * Cancel any outstanding work, if possible. Any future calls to schedule should ignore any work.
   */
  void shutdown();
}