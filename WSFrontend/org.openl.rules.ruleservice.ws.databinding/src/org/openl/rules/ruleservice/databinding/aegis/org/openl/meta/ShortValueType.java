package org.openl.rules.ruleservice.databinding.aegis.org.openl.meta;

/*
 * #%L
 * OpenL - RuleService - RuleService - Web Services Databinding
 * %%
 * Copyright (C) 2013 OpenL Tablets
 * %%
 * See the file LICENSE.txt for copying permission.
 * #L%
 */

import org.apache.cxf.aegis.Context;
import org.apache.cxf.aegis.type.basic.ShortType;
import org.apache.cxf.aegis.xml.MessageReader;
import org.openl.meta.ShortValue;

public class ShortValueType extends ShortType {
    @Override
    public Object readObject(MessageReader reader, Context context) {
        Short value = (Short) super.readObject(reader, context);
        return (value == null) ? null : new ShortValue(value);
    }
}