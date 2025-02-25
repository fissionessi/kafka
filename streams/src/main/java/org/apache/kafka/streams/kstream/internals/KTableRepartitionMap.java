/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.StreamsException;
import org.apache.kafka.streams.kstream.KeyValueMapper;
import org.apache.kafka.streams.processor.api.ContextualProcessor;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.processor.internals.InternalProcessorContext;
import org.apache.kafka.streams.state.ValueAndTimestamp;

import java.util.Map;

import static org.apache.kafka.streams.state.ValueAndTimestamp.getValueOrNull;

/**
 * KTable repartition map functions are not exposed to public APIs, but only used for keyed aggregations.
 * <p>
 * Given the input, it can output at most two records (one mapped from old value and one mapped from new value).
 */
public class KTableRepartitionMap<K, V, K1, V1> implements KTableRepartitionMapSupplier<K, V, KeyValue<K1, V1>, K1, V1> {

    private final KTableImpl<K, ?, V> parent;
    private final KeyValueMapper<? super K, ? super V, KeyValue<K1, V1>> mapper;

    KTableRepartitionMap(final KTableImpl<K, ?, V> parent, final KeyValueMapper<? super K, ? super V, KeyValue<K1, V1>> mapper) {
        this.parent = parent;
        this.mapper = mapper;
    }

    @Override
    public Processor<K, Change<V>, K1, Change<V1>> get() {
        return new KTableMapProcessor();
    }

    @Override
    public KTableValueGetterSupplier<K, KeyValue<K1, V1>> view() {
        final KTableValueGetterSupplier<K, V> parentValueGetterSupplier = parent.valueGetterSupplier();

        return new KTableValueGetterSupplier<K, KeyValue<K1, V1>>() {

            public KTableValueGetter<K, KeyValue<K1, V1>> get() {
                return new KTableMapValueGetter(parentValueGetterSupplier.get());
            }

            @Override
            public String[] storeNames() {
                throw new StreamsException("Underlying state store not accessible due to repartitioning.");
            }
        };
    }

    /**
     * @throws IllegalStateException since this method should never be called
     */
    @Override
    public boolean enableSendingOldValues(final boolean forceMaterialization) {
        // this should never be called
        throw new IllegalStateException("KTableRepartitionMap should always require sending old values.");
    }

    private class KTableMapProcessor extends ContextualProcessor<K, Change<V>, K1, Change<V1>> {

        private boolean isNotUpgrade;

        @SuppressWarnings("checkstyle:cyclomaticComplexity")
        private boolean isNotUpgrade(final Map<String, ?> configs) {
            final Object upgradeFrom = configs.get(StreamsConfig.UPGRADE_FROM_CONFIG);
            if (upgradeFrom == null) {
                return true;
            }

            switch ((String) upgradeFrom) {
                case StreamsConfig.UPGRADE_FROM_0100:
                case StreamsConfig.UPGRADE_FROM_0101:
                case StreamsConfig.UPGRADE_FROM_0102:
                case StreamsConfig.UPGRADE_FROM_0110:
                case StreamsConfig.UPGRADE_FROM_10:
                case StreamsConfig.UPGRADE_FROM_11:
                case StreamsConfig.UPGRADE_FROM_20:
                case StreamsConfig.UPGRADE_FROM_21:
                case StreamsConfig.UPGRADE_FROM_22:
                case StreamsConfig.UPGRADE_FROM_23:
                case StreamsConfig.UPGRADE_FROM_24:
                case StreamsConfig.UPGRADE_FROM_25:
                case StreamsConfig.UPGRADE_FROM_26:
                case StreamsConfig.UPGRADE_FROM_27:
                case StreamsConfig.UPGRADE_FROM_28:
                case StreamsConfig.UPGRADE_FROM_30:
                case StreamsConfig.UPGRADE_FROM_31:
                case StreamsConfig.UPGRADE_FROM_32:
                case StreamsConfig.UPGRADE_FROM_33:
                case StreamsConfig.UPGRADE_FROM_34:
                    return false;
                default:
                    return true;
            }
        }

        @Override
        public void init(final ProcessorContext<K1, Change<V1>> context) {
            super.init(context);
            isNotUpgrade = isNotUpgrade(context().appConfigs());
        }

        /**
         * @throws StreamsException if key is null
         */
        @Override
        public void process(final Record<K, Change<V>> record) {
            // the original key should never be null
            if (record.key() == null) {
                throw new StreamsException("Record key for the grouping KTable should not be null.");
            }

            // if the value is null, we do not need to forward its selected key-value further
            final KeyValue<? extends K1, ? extends V1> newPair = record.value().newValue == null ? null :
                mapper.apply(record.key(), record.value().newValue);
            final KeyValue<? extends K1, ? extends V1> oldPair = record.value().oldValue == null ? null :
                mapper.apply(record.key(), record.value().oldValue);

            // if the selected repartition key or value is null, skip
            // forward oldPair first, to be consistent with reduce and aggregate
            final boolean oldPairNotNull = oldPair != null && oldPair.key != null && oldPair.value != null;
            final boolean newPairNotNull = newPair != null && newPair.key != null && newPair.value != null;
            if (isNotUpgrade && oldPairNotNull && newPairNotNull && oldPair.key.equals(newPair.key)) {
                context().forward(record.withKey(oldPair.key).withValue(new Change<>(newPair.value, oldPair.value)));
            } else {
                if (oldPairNotNull) {
                    context().forward(record.withKey(oldPair.key).withValue(new Change<>(null, oldPair.value)));
                }

                if (newPairNotNull) {
                    context().forward(record.withKey(newPair.key).withValue(new Change<>(newPair.value, null)));
                }
            }

        }
    }

    private class KTableMapValueGetter implements KTableValueGetter<K, KeyValue<K1, V1>> {
        private final KTableValueGetter<K, V> parentGetter;
        private InternalProcessorContext<?, ?> context;

        KTableMapValueGetter(final KTableValueGetter<K, V> parentGetter) {
            this.parentGetter = parentGetter;
        }

        @Override
        public void init(final ProcessorContext<?, ?> context) {
            this.context = (InternalProcessorContext<?, ?>) context;
            parentGetter.init(context);
        }

        @Override
        public ValueAndTimestamp<KeyValue<K1, V1>> get(final K key) {
            return mapValue(key, parentGetter.get(key));
        }

        @Override
        public ValueAndTimestamp<KeyValue<K1, V1>> get(final K key, final long asOfTimestamp) {
            return mapValue(key, parentGetter.get(key, asOfTimestamp));
        }

        @Override
        public boolean isVersioned() {
            return parentGetter.isVersioned();
        }

        @Override
        public void close() {
            parentGetter.close();
        }

        private ValueAndTimestamp<KeyValue<K1, V1>> mapValue(final K key, final ValueAndTimestamp<V> valueAndTimestamp) {
            return ValueAndTimestamp.make(
                mapper.apply(key, getValueOrNull(valueAndTimestamp)),
                valueAndTimestamp == null ? context.timestamp() : valueAndTimestamp.timestamp()
            );
        }
    }

}
