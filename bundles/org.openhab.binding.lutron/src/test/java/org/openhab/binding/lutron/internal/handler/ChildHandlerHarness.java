/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.lutron.internal.handler;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.lutron.internal.protocol.LutronCommandNew;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;

/**
 * Wires a Lutron child handler to a mocked Thing, framework callback and bridge. Commands the handler sends to the
 * bridge are recorded, and status updates are reflected back into the Thing as the framework would do.
 *
 * @author Peter J Wojciechowski - Initial contribution
 */
@NonNullByDefault
class ChildHandlerHarness {
    final ThingUID thingUid;
    final ThingUID bridgeUid = new ThingUID("lutron:ipbridge:test");
    final Thing thing = mock(Thing.class);
    final Bridge bridge = mock(Bridge.class);
    final ThingHandlerCallback callback = mock(ThingHandlerCallback.class);
    final LutronBridgeHandler bridgeHandler;
    final List<LutronCommandNew> sent = new CopyOnWriteArrayList<>();
    private volatile ThingStatusInfo status = new ThingStatusInfo(ThingStatus.INITIALIZING, ThingStatusDetail.NONE,
            null);

    ChildHandlerHarness(String thingTypeId, Map<String, Object> configuration) {
        this(thingTypeId, configuration, IPBridgeHandler.class);
    }

    ChildHandlerHarness(String thingTypeId, Map<String, Object> configuration,
            Class<? extends LutronBridgeHandler> bridgeHandlerType) {
        thingUid = new ThingUID("lutron", thingTypeId, "test");
        bridgeHandler = mock(bridgeHandlerType);
        doAnswer(invocation -> sent.add(invocation.getArgument(0))).when(bridgeHandler)
                .sendCommand(any(LutronCommandNew.class));

        when(bridge.getUID()).thenReturn(bridgeUid);
        when(bridge.getHandler()).thenReturn(bridgeHandler);
        setBridgeStatus(ThingStatus.ONLINE);

        when(thing.getUID()).thenReturn(thingUid);
        when(thing.getBridgeUID()).thenReturn(bridgeUid);
        when(thing.getConfiguration()).thenReturn(new Configuration(configuration));
        when(thing.getStatus()).thenAnswer(invocation -> status.getStatus());
        when(thing.getStatusInfo()).thenAnswer(invocation -> status);

        when(callback.getBridge(bridgeUid)).thenReturn(bridge);
        doAnswer(invocation -> {
            status = invocation.getArgument(1);
            return null;
        }).when(callback).statusUpdated(eq(thing), any());
    }

    void setBridgeStatus(ThingStatus bridgeStatus) {
        when(bridge.getStatus()).thenReturn(bridgeStatus);
        when(bridge.getStatusInfo()).thenReturn(new ThingStatusInfo(bridgeStatus, ThingStatusDetail.NONE, null));
    }

    ThingStatusInfo status() {
        return status;
    }

    ChannelUID channel(String id) {
        return new ChannelUID(thingUid, id);
    }

    /**
     * Returns the LIP form of every command sent so far, and forgets them.
     */
    List<String> takeSent() {
        List<String> lip = sent.stream().map(LutronCommandNew::lipCommand).toList();
        sent.clear();
        return lip;
    }
}
