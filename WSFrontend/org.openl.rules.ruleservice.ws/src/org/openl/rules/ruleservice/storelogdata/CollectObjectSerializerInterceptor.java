package org.openl.rules.ruleservice.storelogdata;

import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.interceptor.StaxOutInterceptor;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;
import org.apache.cxf.phase.Phase;
import org.openl.rules.ruleservice.storelogdata.ObjectSerializer;
import org.openl.rules.ruleservice.storelogdata.StoreLogData;
import org.openl.rules.ruleservice.storelogdata.StoreLogDataHolder;

/**
 * CXF interceptor for collecting object serializer.
 *
 * @author Marat Kamalov
 *
 */
public class CollectObjectSerializerInterceptor extends AbstractPhaseInterceptor<Message> {

    private ObjectSerializer objectSerializer;

    public CollectObjectSerializerInterceptor(String phase, ObjectSerializer objectSerializer) {
        super(phase);
        addBefore(StaxOutInterceptor.class.getName());
        this.objectSerializer = objectSerializer;
    }

    public CollectObjectSerializerInterceptor(ObjectSerializer objectSerializer) {
        this(Phase.PRE_STREAM, objectSerializer);
    }

    @Override
    public void handleMessage(Message message) throws Fault {
        injectObjectSerializer(message);
    }

    @Override
    public void handleFault(Message message) {
        injectObjectSerializer(message);
    }

    private void injectObjectSerializer(Message message) {
        StoreLogData storeLogData = StoreLogDataHolder.get();
        storeLogData.setObjectSerializer(objectSerializer);
    }
}
