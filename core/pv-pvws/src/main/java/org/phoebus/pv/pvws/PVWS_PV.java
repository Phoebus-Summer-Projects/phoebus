package org.phoebus.pv.pvws;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.reactivex.rxjava3.disposables.Disposable;

import org.epics.vtype.VType;
import org.phoebus.pv.PV;

public class PVWS_PV extends PV {
    /**
     * Initialize
     *
     * @param name PV name
     */

    public String baseName;

    protected PVWS_PV(String name, String base_name) throws Exception {
        super(name);
        PVWS_Context context = PVWS_Context.getInstance();
        context.clientSubscribe(base_name);
        PVWS_Context.contextMap.put(base_name, this);

        this.baseName = base_name;

    }

    public void updatePV(VType vval) {
        notifyListenersOfValue(vval);
    }

    public void disconnectPV() {
        this.notifyListenersOfDisconnect();
        PVWS_Context.subscriptions.remove(baseName);
    }

}
