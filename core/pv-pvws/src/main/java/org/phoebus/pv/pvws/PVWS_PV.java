package org.phoebus.pv.pvws;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.reactivex.rxjava3.disposables.Disposable;
import org.epics.vtype.VType;
import org.phoebus.pv.PV;

import java.net.URISyntaxException;

public class PVWS_PV extends PV {
    /**
     * Initialize
     *
     * @param name PV name
     */
    protected PVWS_PV(String name, String base_name) throws Exception {
        super(name);
        PVWS_Context context = PVWS_Context.getInstance();
        context.clientSubscribe(base_name);
        PVWS_Context.contextMap.put(base_name, this);

// Later, when you no longer care about updates


    }

    public void updatePV(VType vval) {
        notifyListenersOfValue(vval);
    }

}
