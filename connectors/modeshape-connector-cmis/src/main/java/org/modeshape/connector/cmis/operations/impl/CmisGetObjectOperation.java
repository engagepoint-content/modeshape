package org.modeshape.connector.cmis.operations.impl;


import org.apache.chemistry.opencmis.client.api.*;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.definitions.TypeDefinition;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.infinispan.schematic.document.Document;
import org.modeshape.connector.cmis.operations.CmisObjectFinderUtil;
import org.modeshape.connector.cmis.Constants;
import org.modeshape.connector.cmis.features.SingleVersionOptions;
import org.modeshape.connector.cmis.mapping.LocalTypeManager;
import org.modeshape.connector.cmis.mapping.MappedCustomType;
import org.modeshape.connector.cmis.ObjectId;
import org.modeshape.connector.cmis.operations.DocumentProducer;
import org.modeshape.jcr.JcrLexicon;
import org.modeshape.jcr.api.JcrConstants;
import org.modeshape.jcr.federation.spi.DocumentWriter;
import org.modeshape.jcr.federation.spi.PageKey;
import org.modeshape.jcr.federation.spi.PageWriter;
import org.modeshape.jcr.value.BinaryValue;

import javax.jcr.nodetype.NodeType;
import java.io.InputStream;
import java.util.*;

public class CmisGetObjectOperation extends CmisOperation {

    private boolean addRequiredPropertiesOnRead;
    private boolean hideRootFolderReference;
    private DocumentProducer documentProducer;
    private String projectedNodeId;
    private String remoteUnfiledNodeId;
    private String commonIdPropertyName;
    private SingleVersionOptions singleVersionOptions;
    long pageSize;
    private boolean folderSetUnknownChildren;
    private String unfiledQueryTemplate;

    public CmisGetObjectOperation(Session session, LocalTypeManager localTypeManager,
                                  boolean addRequiredPropertiesOnRead, boolean hideRootFolderReference,
                                  String projectedNodeId,
                                  String remoteUnfiledNodeId,
                                  SingleVersionOptions singleVersionOptions,
                                  DocumentProducer documentProducer,
                                  CmisObjectFinderUtil finderUtil,
                                  long pageSize, boolean folderSetUnknownChildren,
                                  String unfiledQueryTemplate) {
        super(session, localTypeManager, finderUtil);
        this.addRequiredPropertiesOnRead = addRequiredPropertiesOnRead;
        this.hideRootFolderReference = hideRootFolderReference;
        this.documentProducer = documentProducer;
        this.projectedNodeId = projectedNodeId;
        this.remoteUnfiledNodeId = remoteUnfiledNodeId;
        this.commonIdPropertyName = singleVersionOptions.getCommonIdPropertyName();
        this.singleVersionOptions = singleVersionOptions;
        this.pageSize= pageSize;
        this.folderSetUnknownChildren = folderSetUnknownChildren;
        this.unfiledQueryTemplate = unfiledQueryTemplate;
    }


    /**
     * Translates CMIS folder object to JCR node
     *
     * @param cmisObject CMIS folder object
     * @return JCR node document.
     */
    public DocumentWriter cmisFolder(CmisObject cmisObject) {
        CmisGetChildrenOperation childrenOperation =
                new CmisGetChildrenOperation(session, localTypeManager, remoteUnfiledNodeId,
                        singleVersionOptions, finderUtil, pageSize, folderSetUnknownChildren, unfiledQueryTemplate);

        Folder folder = (Folder) cmisObject;
        DocumentWriter writer = documentProducer.getNewDocument(ObjectId.toString(ObjectId.Type.OBJECT, folder.getId()));
        // set correct type
        writer.setPrimaryType(localTypeManager.cmisTypeToJcr(cmisObject.getType().getId()).getJcrName());
        // parent
        writer.setParent(folder.getParentId());
        // properties
        cmisProperties(folder, writer);
        // children
        childrenOperation.cmisChildren(folder, writer);

        // append repository information to the root node
        if (folder.isRootFolder() && !hideRootFolderReference) {
            writer.addChild(ObjectId.toString(ObjectId.Type.REPOSITORY_INFO, ""), Constants.REPOSITORY_INFO_NODE_NAME);
        }
        if (cmisObject.getId().equals(projectedNodeId) || cmisObject.getId().equals(projectedNodeId)) {
            writer.addChild(ObjectId.toString(ObjectId.Type.UNFILED_STORAGE, ""), ObjectId.Type.UNFILED_STORAGE.getValue());
        }
        // mandatory mixins
        writer.addMixinType(NodeType.MIX_REFERENCEABLE);
        writer.addProperty(JcrLexicon.UUID, cmisObject.getId());
        //
        writer.addMixinType(NodeType.MIX_LAST_MODIFIED);
        Property<Object> lastModified = folder.getProperty(PropertyIds.LAST_MODIFICATION_DATE);
        Property<Object> lastModifiedBy = folder.getProperty(PropertyIds.LAST_MODIFIED_BY);
        writer.addProperty(JcrLexicon.LAST_MODIFIED, localTypeManager.getPropertyUtils().jcrValues(lastModified));
        writer.addProperty(JcrLexicon.LAST_MODIFIED_BY, localTypeManager.getPropertyUtils().jcrValues(lastModifiedBy));
        return writer;
    }


    /**
     * Translates cmis document object to JCR node.
     *
     * @param cmisObject cmis document node
     * @param incomingId jcr key by which document is referenced. it is preferable to set it as document id
     * @return JCR node document.
     */
    public Document cmisDocument(CmisObject cmisObject, String incomingId) {
        org.apache.chemistry.opencmis.client.api.Document doc = CmisOperationCommons.asDocument(cmisObject);

        // document and internalId
        DocumentWriter writer = documentProducer.getNewDocument(ObjectId.toString(ObjectId.Type.OBJECT, incomingId));

        // set correct type
        writer.setPrimaryType(localTypeManager.cmisTypeToJcr(cmisObject.getType().getId()).getJcrName());

        // parents
        List<Folder> parents = doc.getParents();
        ArrayList<String> parentIds = new ArrayList<String>();
        for (Folder f : parents) {
            parentIds.add(ObjectId.toString(ObjectId.Type.OBJECT, f.getId()));
        }
        // no parents = unfiled
        if (parentIds.isEmpty()) parentIds.add(ObjectId.toString(ObjectId.Type.UNFILED_STORAGE, ""));

        // set parents
        writer.setParents(parentIds);

        // content node - mandatory child for a document
        writer.addChild(ObjectId.toString(ObjectId.Type.CONTENT, incomingId), JcrConstants.JCR_CONTENT);

        // basic properties
        cmisProperties(doc, writer);

        writer.addMixinType(NodeType.MIX_REFERENCEABLE);
        writer.addProperty(JcrLexicon.UUID, incomingId);

        writer.addMixinType(NodeType.MIX_LAST_MODIFIED);
        Property<Object> lastModified = doc.getProperty(PropertyIds.LAST_MODIFICATION_DATE);
        Property<Object> lastModifiedBy = doc.getProperty(PropertyIds.LAST_MODIFIED_BY);
        writer.addProperty(JcrLexicon.LAST_MODIFIED, localTypeManager.getPropertyUtils().jcrValues(lastModified));
        writer.addProperty(JcrLexicon.LAST_MODIFIED_BY, localTypeManager.getPropertyUtils().jcrValues(lastModifiedBy));

        return writer.document();
    }

    /**
     * Converts binary content into JCR node.
     *
     * @param id the id of the CMIS document.
     * @return JCR node representation.
     */
    public Document cmisContent(String id) {
        String contentId = ObjectId.toString(ObjectId.Type.CONTENT, id);
        DocumentWriter writer = documentProducer.getNewDocument(contentId);

        org.apache.chemistry.opencmis.client.api.Document doc = CmisOperationCommons.asDocument(finderUtil.find(id));
        writer.setPrimaryType(NodeType.NT_RESOURCE);
        writer.setParent(id);

        if (doc.getContentStream() != null) {
            InputStream is = doc.getContentStream().getStream();
            BinaryValue content = localTypeManager.getFactories().getBinaryFactory().create(is);
            writer.addProperty(JcrConstants.JCR_DATA, content);
            writer.addProperty(JcrConstants.JCR_MIME_TYPE, doc.getContentStream().getMimeType());
        }

        // reference
        writer.addMixinType(NodeType.MIX_REFERENCEABLE);
        writer.addProperty(JcrLexicon.UUID, contentId);

        Property<Object> lastModified = doc.getProperty(PropertyIds.LAST_MODIFICATION_DATE);
        Property<Object> lastModifiedBy = doc.getProperty(PropertyIds.LAST_MODIFIED_BY);

        writer.addProperty(JcrLexicon.LAST_MODIFIED, localTypeManager.getPropertyUtils().jcrValues(lastModified));
        writer.addProperty(JcrLexicon.LAST_MODIFIED_BY, localTypeManager.getPropertyUtils().jcrValues(lastModifiedBy));

        writer.addMixinType(NodeType.MIX_CREATED);
        Property<Object> created = doc.getProperty(PropertyIds.CREATION_DATE);
        Property<Object> createdBy = doc.getProperty(PropertyIds.CREATED_BY);
        writer.addProperty(JcrLexicon.CREATED, localTypeManager.getPropertyUtils().jcrValues(created));
        writer.addProperty(JcrLexicon.CREATED_BY, localTypeManager.getPropertyUtils().jcrValues(createdBy));

        return writer.document();
    }

    /**
     * Translates CMIS repository information into Node.
     *
     * @return node document.
     */
    public Document jcrUnfiled(String originalId, String caughtProjectedId) {
        DocumentWriter writer = documentProducer.getNewDocument(ObjectId.toString(ObjectId.Type.OBJECT, ObjectId.Type.UNFILED_STORAGE.getValue()));
        Folder root = session.getRootFolder();

        writer.setPrimaryType(NodeType.NT_FOLDER);
        if (caughtProjectedId == null) {
            // replace with logger lately
            System.out.println("Caught ROOT node as NULL when filling Unfiled node!!..!!");
        }
        writer.setParent(caughtProjectedId);
//        writer.setParent("[root]");

        writer.addMixinType(NodeType.MIX_REFERENCEABLE);
        writer.addProperty(JcrLexicon.UUID, ObjectId.Type.UNFILED_STORAGE.getValue());

        writer.addMixinType(NodeType.MIX_LAST_MODIFIED);
        Property<Object> lastModified = root.getProperty(PropertyIds.LAST_MODIFICATION_DATE);
        Property<Object> lastModifiedBy = root.getProperty(PropertyIds.LAST_MODIFIED_BY);
        writer.addProperty(JcrLexicon.LAST_MODIFIED, localTypeManager.getPropertyUtils().jcrValues(lastModified));
        writer.addProperty(JcrLexicon.LAST_MODIFIED_BY, localTypeManager.getPropertyUtils().jcrValues(lastModifiedBy));

        if (originalId.contains("#")) {
            CmisGetChildrenOperation childrenOperation =
                    new CmisGetChildrenOperation(session, localTypeManager, remoteUnfiledNodeId,
                            singleVersionOptions, finderUtil, pageSize, folderSetUnknownChildren, unfiledQueryTemplate);
            childrenOperation.getChildren(new PageKey(originalId), writer);
        } else {
            writer.addPage(ObjectId.toString(ObjectId.Type.UNFILED_STORAGE, ""), 0, pageSize, PageWriter.UNKNOWN_TOTAL_SIZE);
        }

        return writer.document();
    }

    /**
     * Converts CMIS object's properties to JCR node localTypeManager.getPropertyUtils().
     *
     * @param object CMIS object
     * @param writer JCR node representation.
     */
    private void cmisProperties(CmisObject object,
                                DocumentWriter writer) {
        // convert properties
        List<Property<?>> cmisProperties = object.getProperties();
        TypeDefinition type = object.getType();
        MappedCustomType typeMapping = localTypeManager.getMappedTypes().findByExtName(type.getId());

        Set<String> propertyDefinitions = new LinkedHashSet<String>(type.getPropertyDefinitions().keySet());

        Map<String, Object[]> propMap = processProperties(cmisProperties, type, typeMapping, propertyDefinitions);
        for (Map.Entry<String, Object[]> entry : propMap.entrySet()) {
            writer.addProperty(entry.getKey(), entry.getValue());
        }


        // error protection
        if (addRequiredPropertiesOnRead) {
            for (String requiredExtProperty : propertyDefinitions) {
                PropertyDefinition<?> propertyDefinition = type.getPropertyDefinitions().get(requiredExtProperty);
                if (propertyDefinition.isRequired() && propertyDefinition.getUpdatability() == Updatability.READWRITE && !requiredExtProperty.startsWith(Constants.CMIS_PREFIX)) {
                    String pname = localTypeManager.getPropertyUtils().findJcrName(requiredExtProperty);
                    String propertyTargetName = typeMapping.toJcrProperty(pname);
                    writer.addProperty(propertyTargetName, CmisOperationCommons.getRequiredPropertyValue(propertyDefinition));
                }
            }
        }
    }

    public Map<String, Object[]> processProperties(List<Property<?>> cmisProperties, TypeDefinition type, MappedCustomType typeMapping, Set<String> propertyDefinitions) {
        Map<String, Object[]> result = new LinkedHashMap<String, Object[]>(cmisProperties.size());
        for (Property<?> cmisProperty : cmisProperties) {
            // pop item prom list
            propertyDefinitions.remove(cmisProperty.getId());
            //
            PropertyDefinition<?> propertyDefinition = type.getPropertyDefinitions().get(cmisProperty.getId());
            String jcrPropertyName = typeMapping.toJcrProperty(cmisProperty.getId());

            // filtered = ignored
            if (jcrPropertyName == null) continue;
            // if unhandled continue = ignore
            if (!getShouldHandleCustomProperty(type, typeMapping, jcrPropertyName, propertyDefinition)) continue;

            // now handle -> it is our custom property or basic jcr one
            Object[] values = localTypeManager.getPropertyUtils().jcrValues(cmisProperty);
            result.put(jcrPropertyName, values);
        }

        return result;
    }

    /*
    * custom property filter criteria
    */
    private boolean getShouldHandleCustomProperty(TypeDefinition type, MappedCustomType typeMapping, String jcrPropertyName, PropertyDefinition<?> propertyDefinition) {
        // should be no additional properties for basic types
        boolean noCustomAllowed = type.getId().equals(BaseTypeId.CMIS_DOCUMENT.value());
        // we ignore RO properties as they are mostly system specific additionals
        // when handled -> increases mapping size
        boolean readOnly = propertyDefinition.getUpdatability() == Updatability.READONLY;
        boolean isCustom = !jcrPropertyName.startsWith("jcr:");

        boolean isUnhandledCustomProp = (isCustom) && (readOnly || noCustomAllowed);
        if (isUnhandledCustomProp) return false;

        // jcr analog not found but property is not our custom one
        boolean isUnknown = jcrPropertyName.startsWith(Constants.CMIS_PREFIX);
        if (isUnknown) return false;

        return true;
    }
}
