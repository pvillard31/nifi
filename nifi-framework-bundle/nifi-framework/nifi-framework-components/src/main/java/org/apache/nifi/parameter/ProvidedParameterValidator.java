/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.parameter;

import org.apache.nifi.flow.VersionedParameter;
import org.apache.nifi.flow.VersionedParameterContext;
import org.slf4j.Logger;

/**
 * Encodes the framework invariant that a {@link Parameter} with {@code provided = true} must
 * only ever exist inside a {@link ParameterContext} that has a {@link ParameterProvider} bound.
 *
 * <p>The {@code provided} flag drives the persistence and rehydration contract enforced by
 * {@link StandardParameterValueMapper} and
 * {@code org.apache.nifi.controller.serialization.VersionedFlowSynchronizer#createParameterMap},
 * which never persists or reads back the actual value of a provided Parameter; instead, the
 * value must be re-fetched at startup from the bound Parameter Provider. A Parameter Context
 * with no provider therefore cannot satisfy that contract for any {@code provided = true}
 * Parameter, and any such combination is corrupt state that must never be allowed to land on
 * disk.</p>
 *
 * <p>This validator is invoked from every framework write path that constructs a runtime
 * {@link Parameter} or a {@link VersionedParameter} from external input (REST DAO, runtime
 * versioned-flow synchronizer and startup flow synchronizer). When the invariant is violated
 * the {@code provided} flag is forced to {@code false} and an ERROR is logged so operators
 * have an actionable record of the upstream bug.</p>
 */
public final class ProvidedParameterValidator {

    private ProvidedParameterValidator() {
    }

    /**
     * Normalize a runtime {@link Parameter} that is about to be applied to the given
     * {@link ParameterContext}. If the parameter is marked {@code provided = true} but the
     * target context has no Parameter Provider bound, an ERROR is logged and a copy of the
     * parameter with {@code provided = false} is returned. Any other parameter is returned
     * unchanged.
     *
     * @param parameter  the proposed parameter, may be {@code null}
     * @param context    the target parameter context, may be {@code null} when the context
     *                   is being constructed and no provider could possibly be bound yet
     * @param logger     SLF4J logger for the calling site
     * @param entryPoint short human-readable description of the calling code path, used in
     *                   the diagnostic log message (for example {@code "REST DAO"} or
     *                   {@code "flow version update"})
     * @return the original parameter, or a normalized copy with {@code provided = false}
     */
    public static Parameter normalizeForContext(final Parameter parameter,
                                                final ParameterContext context,
                                                final Logger logger,
                                                final String entryPoint) {
        if (parameter == null || !parameter.isProvided()) {
            return parameter;
        }
        if (context != null && context.getParameterProvider() != null) {
            return parameter;
        }

        final String parameterName = parameter.getDescriptor().getName();
        final String contextName = context == null ? "<unbound>" : context.getName();
        logger.error("Invariant violation while {}: Parameter [{}] in Parameter Context [{}] declared provided=true but the context has no Parameter Provider bound. " +
                "Forcing provided=false. This indicates corrupted upstream input; investigate the caller.",
                entryPoint, parameterName, contextName);

        return new Parameter.Builder()
                .fromParameter(parameter)
                .provided(false)
                .build();
    }

    /**
     * Normalize a {@link VersionedParameter} that is about to be applied during flow load.
     * Unlike {@link #normalizeForContext(Parameter, ParameterContext, Logger, String)} the
     * runtime {@link ParameterContext} typically does not exist yet at this point, so the
     * provider binding is consulted on the {@link VersionedParameterContext} instead.
     *
     * <p>If the versioned parameter is marked {@code provided = true} but the owning
     * versioned context has no parameter provider configured, an ERROR is logged and a copy
     * of the versioned parameter with {@code provided = false} is returned. Any other
     * versioned parameter is returned unchanged.</p>
     *
     * @param versioned the proposed versioned parameter, may be {@code null}
     * @param owner     the owning versioned parameter context
     * @param logger    SLF4J logger for the calling site
     * @return the original versioned parameter, or a normalized copy with
     *         {@code provided = false}
     */
    public static VersionedParameter normalizeForVersioned(final VersionedParameter versioned,
                                                           final VersionedParameterContext owner,
                                                           final Logger logger) {
        if (versioned == null || !versioned.isProvided()) {
            return versioned;
        }
        if (owner != null && owner.getParameterProvider() != null) {
            return versioned;
        }

        final String parameterName = versioned.getName();
        final String contextName = owner == null ? "<unbound>" : owner.getName();
        logger.error("Invariant violation while loading flow: Parameter [{}] in Parameter Context [{}] declared provided=true but the context has no Parameter Provider bound. " +
                "Forcing provided=false on load. The persisted state is corrupt and will be healed on the next flow save.",
                parameterName, contextName);

        final VersionedParameter copy = new VersionedParameter();
        copy.setName(versioned.getName());
        copy.setDescription(versioned.getDescription());
        copy.setSensitive(versioned.isSensitive());
        copy.setValue(versioned.getValue());
        copy.setReferencedAssets(versioned.getReferencedAssets());
        copy.setProvided(false);
        return copy;
    }
}
