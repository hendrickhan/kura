/*******************************************************************************
 * Copyright (c) 2016, 2018 Eurotech and/or its affiliates and others
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Eurotech
 *  Amit Kumar Mondal
 *
 *******************************************************************************/
package org.eclipse.kura.internal.wire.helper;

import static java.util.Objects.requireNonNull;
import static org.eclipse.kura.wire.graph.Constants.WIRE_EMITTER_PORT_PROP_NAME;
import static org.eclipse.kura.wire.graph.Constants.WIRE_RECEIVER_PORT_PROP_NAME;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.eclipse.kura.localization.LocalizationAdapter;
import org.eclipse.kura.localization.resources.WireMessages;
import org.eclipse.kura.util.collection.CollectionUtil;
import org.eclipse.kura.wire.WireComponent;
import org.eclipse.kura.wire.WireEnvelope;
import org.eclipse.kura.wire.WireReceiver;
import org.eclipse.kura.wire.WireRecord;
import org.eclipse.kura.wire.WireSupport;
import org.eclipse.kura.wire.graph.EmitterPort;
import org.eclipse.kura.wire.graph.MultiportWireSupport;
import org.eclipse.kura.wire.graph.Port;
import org.eclipse.kura.wire.graph.ReceiverPort;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.wireadmin.Wire;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Class WireSupportImpl implements {@link WireSupport}
 */
final class WireSupportImpl implements WireSupport, MultiportWireSupport {

    private static final Logger logger = LoggerFactory.getLogger(WireSupportImpl.class);

    private static final WireMessages message = LocalizationAdapter.adapt(WireMessages.class);

    private final EventAdmin eventAdmin;

    private final List<ReceiverPort> receiverPorts;

    private final List<EmitterPort> emitterPorts;

    private final WireComponent wireComponent;

    private final String servicePid;

    private final String kuraServicePid;

    private final Map<Wire, ReceiverPortImpl> receiverPortByWire;

    private final BiConsumer<Wire, WireEnvelope> updateFunc;

    WireSupportImpl(final WireComponent wireComponent, final String servicePid, final String kuraServicePid,
            final EventAdmin eventAdmin, int inputPortCount, int outputPortCount) {
        requireNonNull(wireComponent, message.wireSupportedComponentNonNull());
        requireNonNull(eventAdmin, message.eventAdminNonNull());
        requireNonNull(servicePid, "service pid cannot be null");
        requireNonNull(kuraServicePid, "kura service pid cannot be null");

        this.servicePid = servicePid;
        this.kuraServicePid = kuraServicePid;
        this.wireComponent = wireComponent;
        this.eventAdmin = eventAdmin;

        if (inputPortCount < 0) {
            throw new IllegalArgumentException("Input port count must be greater or equal than zero");
        }
        if (outputPortCount < 0) {
            throw new IllegalArgumentException("Output port count must be greater or equal than zero");
        }

        this.receiverPorts = new ArrayList<>(inputPortCount);
        this.emitterPorts = new ArrayList<>(outputPortCount);
        this.receiverPortByWire = new HashMap<>();

        if (wireComponent instanceof WireReceiver) {
            this.updateFunc = this::updatedLegacy;
        } else {
            this.updateFunc = this::updatedMultiport;
        }

        for (int i = 0; i < inputPortCount; i++) {
            receiverPorts.add(new ReceiverPortImpl());
        }

        for (int i = 0; i < outputPortCount; i++) {
            emitterPorts.add(new EmitterPortImpl(i));
        }
    }

    private void clearReceiverPorts() {
        this.receiverPortByWire.clear();
        for (final ReceiverPort port : this.receiverPorts) {
            ((PortImpl) port).connectedWires.clear();
        }
    }

    private void clearEmitterPorts() {
        for (final EmitterPort port : this.emitterPorts) {
            ((PortImpl) port).connectedWires.clear();
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void consumersConnected(final Wire[] wires) {
        clearEmitterPorts();
        if (wires == null) {
            return;
        }
        for (Wire w : wires) {
            try {
                final int outputPort = (Integer) w.getProperties().get(WIRE_EMITTER_PORT_PROP_NAME.value());
                ((PortImpl) this.emitterPorts.get(outputPort)).connectedWires.add(w);
            } catch (Exception e) {
                logger.warn("Failed to assign outgoing wire to port", e);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void emit(final List<WireRecord> wireRecords) {
        requireNonNull(wireRecords, message.wireRecordsNonNull());
        final WireEnvelope envelope = createWireEnvelope(wireRecords);
        for (EmitterPort emitterPort : this.emitterPorts) {
            emitterPort.emit(envelope);
        }
    }

    /** {@inheritDoc} */
    @Override
    public synchronized Object polled(final Wire wire) {
        return wire.getLastValue();
    }

    /** {@inheritDoc} */
    @Override
    public synchronized void producersConnected(final Wire[] wires) {
        clearReceiverPorts();
        if (wires == null) {
            return;
        }
        for (Wire w : wires) {
            try {
                final int receiverPortIndex = (Integer) w.getProperties().get(WIRE_RECEIVER_PORT_PROP_NAME.value());
                final ReceiverPortImpl receiverPort = (ReceiverPortImpl) this.receiverPorts.get(receiverPortIndex);
                receiverPort.connectedWires.add(w);
                this.receiverPortByWire.put(w, receiverPort);
            } catch (Exception e) {
                logger.warn("Failed to assign incomimg wire to port", e);
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void updated(final Wire wire, final Object value) {
        if (wire == null) {
            logger.warn("{}", message.wireNonNull());
            return;
        }
        this.updateFunc.accept(wire, (WireEnvelope) value);
    }

    private void updatedLegacy(final Wire wire, final WireEnvelope envelope) {
        ((WireReceiver) this.wireComponent).onWireReceive(envelope);
    }

    private void updatedMultiport(final Wire wire, final WireEnvelope envelope) {
        final ReceiverPortImpl receiverPort = this.receiverPortByWire.get(wire);
        receiverPort.consumer.accept(envelope);
    }

    @Override
    public List<EmitterPort> getEmitterPorts() {
        return Collections.unmodifiableList(this.emitterPorts);
    }

    @Override
    public List<ReceiverPort> getReceiverPorts() {
        return Collections.unmodifiableList(this.receiverPorts);
    }

    private abstract class PortImpl implements Port {

        List<Wire> connectedWires = new CopyOnWriteArrayList<>();

        @Override
        public List<Wire> listConnectedWires() {
            return Collections.unmodifiableList(connectedWires);
        }
    }

    private class EmitterPortImpl extends PortImpl implements EmitterPort {

        private Event emitEvent;

        public EmitterPortImpl(int index) {
            final Map<String, Object> eventProperties = CollectionUtil.newHashMap();
            eventProperties.put("emitter", kuraServicePid);
            eventProperties.put("port", index);
            this.emitEvent = new Event(WireSupport.EMIT_EVENT_TOPIC, eventProperties);
        }

        @Override
        public void emit(WireEnvelope envelope) {
            for (final Wire wire : this.connectedWires) {
                wire.update(envelope);
            }
            eventAdmin.postEvent(emitEvent);
        }

    }

    private class ReceiverPortImpl extends PortImpl implements ReceiverPort {

        Consumer<WireEnvelope> consumer = envelope -> {
            // do nothing
        };

        @Override
        public void onWireReceive(Consumer<WireEnvelope> consumer) {
            requireNonNull(consumer);
            this.consumer = consumer;
        }
    }

    @Override
    public WireEnvelope createWireEnvelope(List<WireRecord> records) {
        return new WireEnvelope(servicePid, records);
    }
}
