package org.modeshape.connector.cmis.operations.impl;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.exceptions.CmisObjectNotFoundException;
import org.infinispan.schematic.document.Document;
import org.modeshape.connector.cmis.mapping.LocalTypeManager;
import org.modeshape.connector.cmis.mapping.MappedCustomType;
import org.modeshape.connector.cmis.ObjectId;
import org.modeshape.connector.cmis.operations.BinaryContentProducerInterface;
import org.modeshape.jcr.federation.spi.DocumentChanges;
import org.modeshape.jcr.value.Name;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.modeshape.connector.cmis.operations.impl.CmisOperationCommons.asDocument;
import static org.modeshape.connector.cmis.operations.impl.CmisOperationCommons.isVersioned;


public class CmisUpdateOperation extends CmisOperation {

    private boolean ignoreEmptyPropertiesOnCreate;

    public CmisUpdateOperation(Session session, LocalTypeManager localTypeManager, boolean ignoreEmptyPropertiesOnCreate) {
        super(session, localTypeManager);
        this.ignoreEmptyPropertiesOnCreate = ignoreEmptyPropertiesOnCreate;
    }

    public void updateDocument(DocumentChanges delta, BinaryContentProducerInterface binaryProducer) {
        // object id is a composite key which holds information about
        // unique object identifier and about its type
        ObjectId objectId = ObjectId.valueOf(delta.getDocumentId());

        // this action depends from object type
        switch (objectId.getType()) {
            case REPOSITORY_INFO:
                // repository node is read only
                break;

            case CONTENT:
                // in the jcr domain content is represented by child node of
                // the nt:file node while in cmis domain it is a property of
                // the cmis:document object. so to perform this operation we need
                // to restore identifier of the original cmis:document. it is easy
                String cmisId = objectId.getIdentifier();

                // now let's get the reference to this object
                CmisObject cmisObject = session.getObject(cmisId);

                if (cmisObject == null) {
                    throw new CmisObjectNotFoundException("Cannot find CMIS object with id: " + cmisId);
                }

                // for this case we have the only property - jcr:data
                DocumentChanges.PropertyChanges changes = delta.getPropertyChanges();

                if (!changes.getRemoved().isEmpty()) {
                    if (isVersioned(cmisObject)) {
                        CmisOperationCommons.updateVersionedDoc(session, cmisObject, null, null);   // todo check if it removes
                    } else {
                        asDocument(cmisObject).deleteContentStream();
                    }
                } else {
                    ContentStream stream = binaryProducer.jcrBinaryContent(delta.getDocument());

                    if (stream != null) {
                        if (isVersioned(cmisObject)) {
                            if (isVersioned(cmisObject))
                                CmisOperationCommons.updateVersionedDoc(session, cmisObject, null, stream);
                        } else {
                            asDocument(cmisObject).setContentStream(stream, true);
                        }
                    }
                }
                break;

            case OBJECT:
                // modifying cmis:folders and cmis:documents
                cmisObject = session.getObject(objectId.getIdentifier());
                changes    = delta.getPropertyChanges();


                // process children changes TODO TODO
                if (delta.getChildrenChanges().getRenamed().size() > 0) {
                    debug("Children changes: renamed", Integer.toString(delta.getChildrenChanges().getRenamed().size()));

                    for (Map.Entry<String, Name> entry : delta.getChildrenChanges().getRenamed().entrySet()) {
                        debug("Child renamed", entry.getKey(), " = ", entry.getValue().toString());

                        CmisObject childCmisObject = session.getObject(entry.getKey());
                        Map<String, Object> updProperties = new HashMap<String, Object>();

                        updProperties.put("cmis:name", entry.getValue().getLocalName());

                        if ((cmisObject instanceof org.apache.chemistry.opencmis.client.api.Document) && isVersioned(cmisObject)) {
                            CmisOperationCommons.updateVersionedDoc(session, childCmisObject, updProperties, null);
                        } else {
                            childCmisObject.updateProperties(updProperties);
                        }
                    }
                }

                Document props = delta.getDocument().getDocument("properties");

                // checking that object exists
                if (cmisObject == null) {
                    throw new CmisObjectNotFoundException("Cannot find CMIS object with id: " + objectId.getIdentifier());
                }

                // Prepare store for the cmis properties
                Map<String, Object> updateProperties = new HashMap<String, Object>();

                // ask cmis repository to get property definitions
                // we will use these definitions for correct conversation
                Map<String, PropertyDefinition<?>> propDefs = cmisObject.getBaseType().getPropertyDefinitions();
                propDefs.putAll(cmisObject.getType().getPropertyDefinitions());

                // group added and modified properties
                ArrayList<Name> modifications = new ArrayList<Name>();
                modifications.addAll(changes.getAdded());
                modifications.addAll(changes.getChanged());

                MappedCustomType mapping = localTypeManager.getMappedTypes().findByExtName(cmisObject.getType().getId());

                // convert names and values
                for (Name name : modifications) {
                    // prefix
                    debug("name.getNamespaceUri()", name.getNamespaceUri());
                    String prefix = localTypeManager.getPrefixes().value(name.getNamespaceUri());
                    debug("prefix", prefix);

                    // prefixed name of the property in jcr domain is
                    String jcrPropertyName = prefix != null ? prefix + ":" + name.getLocalName() : name.getLocalName();
                    debug("jcrPName", jcrPropertyName);

                    // the name of this property in cmis domain is
                    String cmisPropertyName = localTypeManager.getPropertyUtils().findCmisName(jcrPropertyName);
                    debug("cmisName", cmisPropertyName);

                    // correct. AAA!!!
                    cmisPropertyName = mapping.toExtProperty(cmisPropertyName);
                    debug("cmis replaced name", cmisPropertyName);

                    // in cmis domain this property is defined as
                    PropertyDefinition<?> pdef = propDefs.get(cmisPropertyName);

                    // unknown property?
                    if (pdef == null) {
                        // ignore
                        continue;
                    }

                    // convert value and store
                    Document jcrValues = props.getDocument(name.getNamespaceUri());

                    Object value = localTypeManager.getPropertyUtils().cmisValue(pdef, name.getLocalName(), jcrValues);
                    // ! error protection
                    if (ignoreEmptyPropertiesOnCreate && pdef.isRequired() && value == null) {
                        debug("WARNING: property [", cmisPropertyName, "] is required but EMPTY !!!!");
                        continue;
                    }

                    debug("adding prop", cmisPropertyName, "value", value.toString());
                    updateProperties.put(cmisPropertyName, value);
                }

                // step #2: nullify removed properties
                for (Name name : changes.getRemoved()) {
                    String prefix = localTypeManager.getPrefixes().value(name.getNamespaceUri());

                    // prefixed name of the property in jcr domain is
                    String jcrPropertyName = prefix != null ? prefix + ":" + name.getLocalName() : name.getLocalName();
                    // the name of this property in cmis domain is
                    String cmisPropertyName = localTypeManager.getPropertyUtils().findCmisName(jcrPropertyName);

                    // correlate with mapping
                    cmisPropertyName = mapping.toExtProperty(cmisPropertyName);

                    // in cmis domain this property is defined as
                    PropertyDefinition<?> pdef = propDefs.get(cmisPropertyName);

                    // unknown property? - ignore
                    if (pdef == null) {
                        debug(cmisPropertyName, "unknown property - ignore ..");
                        continue;
                    }

                    updateProperties.put(cmisPropertyName, null);
                }

                // run update action
                debug("update properties?? ", updateProperties.isEmpty() ? "No" : "Yep");
                if (!updateProperties.isEmpty()) {
                    if ((cmisObject instanceof org.apache.chemistry.opencmis.client.api.Document) && isVersioned(cmisObject)) {
                        CmisOperationCommons.updateVersionedDoc(session, cmisObject, updateProperties, null);
                    } else {
                        cmisObject.updateProperties(updateProperties);
                    }
                }

                break;
        }
        debug("end of update story -----------------------------");

    }

}