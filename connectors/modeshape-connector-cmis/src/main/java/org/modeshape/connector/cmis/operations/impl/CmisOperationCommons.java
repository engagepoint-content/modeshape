package org.modeshape.connector.cmis.operations.impl;


import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.definitions.DocumentTypeDefinition;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.Cardinality;
import org.apache.chemistry.opencmis.commons.exceptions.CmisBaseException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisInvalidArgumentException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisRuntimeException;

import java.util.Collections;
import java.util.Map;

public class CmisOperationCommons {

    public static String updateVersionedDoc(Session session, CmisObject cmisObject, Map<String, ?> properties, ContentStream contentStream) {
        org.apache.chemistry.opencmis.client.api.Document pwc = checkout(session, cmisObject);
        return checkin(pwc, properties, contentStream);
    }

    public static boolean isDocument(CmisObject cmisObject) {
        return cmisObject instanceof org.apache.chemistry.opencmis.client.api.Document;
    }

    public static org.apache.chemistry.opencmis.client.api.Document asDocument(CmisObject cmisObject) {
        if (!isDocument(cmisObject))
            throw new CmisInvalidArgumentException("Object is not a document: "
                    + cmisObject.getId()
                    + " with type "
                    + cmisObject.getType().getId());
        return (org.apache.chemistry.opencmis.client.api.Document) cmisObject;
    }

    public static boolean isVersioned(CmisObject cmisObject) {
        ObjectType objectType = cmisObject.getType();
        if (objectType instanceof DocumentTypeDefinition) {
            DocumentTypeDefinition docType = (DocumentTypeDefinition) objectType;
            return docType.isVersionable();
        }

        return false;
    }

    public static org.apache.chemistry.opencmis.client.api.Document checkout(Session session, CmisObject cmisObject) {
        org.apache.chemistry.opencmis.client.api.Document doc = (org.apache.chemistry.opencmis.client.api.Document) cmisObject;
        org.apache.chemistry.opencmis.client.api.ObjectId pwcId = doc.checkOut();
        return asDocument(session.getObject(pwcId));
    }


    public static String checkin(org.apache.chemistry.opencmis.client.api.Document pwc, Map<String, ?> properties, ContentStream contentStream) {
        try {
            return pwc.checkIn(true, properties, contentStream, "connector's check in").getId();
        } catch (CmisBaseException e) {
            pwc.cancelCheckOut();
            throw new CmisRuntimeException(e.getMessage(), e);
        }
//        return pwc.getId();
    }


    // todo improve this logic
    public static Object getRequiredPropertyValue(PropertyDefinition<?> remotePropDefinition) {
        if (remotePropDefinition.getCardinality() == Cardinality.MULTI)
            return Collections.singletonList("");
        return remotePropDefinition.getId();
    }
}
