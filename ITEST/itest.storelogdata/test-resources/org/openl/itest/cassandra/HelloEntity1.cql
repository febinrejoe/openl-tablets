CREATE TABLE openl_logging_hello_entity1 (
    id text,
    incomingtime timestamp,
    methodName text,
    outcomingtime timestamp,
    publishertype text,
    request text,
    response text,
    servicename text,
    hour int,
    value text,
    result text,
    objectSerializerFound boolean,
    intValue1 int,
    intValue2 int,
    intValue3 int,
    boolValue1 boolean,
    boolValue2 boolean,
    stringvalue1 text,
    stringvalue2 text,
    stringvalue3 text,
    url text,
    header1 text,
    header2 text,
    intValueToString text,
    PRIMARY KEY (id, publishertype, servicename)
)