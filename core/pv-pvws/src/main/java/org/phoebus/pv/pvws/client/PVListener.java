package org.phoebus.pv.pvws.client;

import org.epics.vtype.VType;

public interface PVListener {
    void onPVUpdate(String pvName, VType value);
}
