package org.openl.rules.ruleservice.logging.cassandra;

import java.lang.reflect.Method;

import org.openl.binding.MethodUtil;
import org.openl.rules.ruleservice.logging.StoreLoggingData;
import org.openl.rules.ruleservice.logging.StoreLoggingDataMapper;
import org.openl.rules.ruleservice.logging.StoreLoggingService;
import org.openl.rules.ruleservice.logging.cassandra.annotation.CassandraEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CassandraStoreLoggingService implements StoreLoggingService {

    private final Logger log = LoggerFactory.getLogger(CassandraStoreLoggingService.class);

    private CassandraOperations cassandraOperations;

    private StoreLoggingDataMapper storeLoggingDataMapper = new StoreLoggingDataMapper();

    public CassandraOperations getCassandraOperations() {
        return cassandraOperations;
    }

    public void setCassandraOperations(CassandraOperations cassandraOperations) {
        this.cassandraOperations = cassandraOperations;
    }

    @Override
    public void store(StoreLoggingData storeLoggingData) {
        Method serviceMethod = storeLoggingData.getServiceMethod();
        if (serviceMethod == null) {
            log.error("Service method has not been found! Please, see previous errors.");
            return;
        }

        Object entity = null;

        CassandraEntity cassandraEntity = serviceMethod.getAnnotation(CassandraEntity.class);
        if (cassandraEntity == null) {
            cassandraEntity = serviceMethod.getDeclaringClass().getAnnotation(CassandraEntity.class);
        }

        if (cassandraEntity == null) {
            entity = new LoggingRecord();
        } else {
            Class<?> entityClass = cassandraEntity.value();
            try {
                entity = entityClass.newInstance();
            } catch (InstantiationException | IllegalAccessException e) {
                if (log.isErrorEnabled()) {
                    log.error(String.format(
                        "Failed to instantiate cassandra entity for '%s' method. Please, check that '%s' class is not abstact and has a default constructor.",
                        MethodUtil.printQualifiedMethodName(serviceMethod),
                        entityClass.getTypeName()), e);
                }
            }
        }
        if (entity != null) {
            storeLoggingDataMapper.map(storeLoggingData, entity);
            cassandraOperations.saveAsync(entity);
        }
    }
}
