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


        // Value listener



        /*this.onValueEvent().subscribe(value -> {
            System.out.println("Received value update for " + name + ": " + value);
        });

        subscribe();

        // ✅ Safe way to listen for access rights updates
        this.onAccessRightsEvent().subscribe(isWritable -> {
            System.out.println("PV " + name + " writable? " + isWritable);
        });*/
        /*Disposable d = this.onValueEvent().subscribe(value -> {
            System.out.println("Got update:😮😮😮😮😮😮😮😮😮😮😮😮😮😮😮😮 " + value);
            notifyListenersOfValue(value);
        });*/

        PVWS_Context.contextMap.put(base_name, this);

// Later, when you no longer care about updates


    }

    public void updatePV(VType vval) {
        notifyListenersOfValue(vval);
    }

    public void disconnectPV() {
        this.notifyListenersOfDisconnect();
    }


}


//CREATE A LISTERER WITH PV
    // controller for the eventhandlers that use procted methods in PV

//INSERT IT INTO THE CONTEXT HASHMAP <PV, LISTENER>
    // keep a hashmap of the pv and event handler
    // it will use the functions in PV to handle updates

//HANDLER LISTERNER IN THIS CLASS TO WRAP PROTECTED METHODS -> USE THE METHODS HERE IN PVWS CONTEXT
    // use the context methods for the listeners to activate subscribe and unsubscribe functionality (INSIDE PHOEBUS)


// CREATE EVENT LISTENER IN PVWS_PV

// INSERT EVENT LISTERNER AND PVWS_PV INTO A HASHMAP IN CONTEXT

// HAVE HIGH LEVEL FUNCTIONALITY IN THE CONTEXT FOR PVWS_PV AND LISTERNERS.

// TRIGGER EVENTS ON UPDATES IN CLIENT ONMESSAGE FUNCTION.