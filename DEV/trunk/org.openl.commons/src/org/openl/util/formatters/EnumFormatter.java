package org.openl.util.formatters;

import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openl.util.EnumUtils;

public class EnumFormatter implements IFormatter {

    private static final Log LOG = LogFactory.getLog(EnumFormatter.class);

    private Class<?> enumClass;
    
    public EnumFormatter(Class<?> enumType) {
        this.enumClass = enumType;
    }
    
    public String format(Object value) {

        if (!(value instanceof Enum<?>)) {
            
            LOG.debug(String.format("Should be a %s value: %s" , enumClass.toString(),
                    ObjectUtils.toString(value, null)));
            return null;
        }
        
        return EnumUtils.getName((Enum<?>)value);        
    }
    
    public Object parse(String value) {
        return EnumUtils.valueOf(enumClass, value);
    }

}
