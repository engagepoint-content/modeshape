/*
 * ModeShape (http://www.modeshape.org)
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * See the AUTHORS.txt file in the distribution for a full listing of
 * individual contributors.
 *
 * ModeShape is free software. Unless otherwise indicated, all code in ModeShape
 * is licensed to you under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * ModeShape is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.modeshape.connector.cmis.mapping;

import java.util.ArrayList;
import java.util.Iterator;
import javax.jcr.nodetype.NodeType;
import org.apache.chemistry.opencmis.commons.enums.BaseTypeId;
import org.modeshape.jcr.value.Name;

/**
 * Maps relations between JCR node types and CMIS object types.
 * 
 * @author kulikov
 */
@SuppressWarnings( "synthetic-access" )
public class Nodes {
    private final static String[] map = new String[] {BaseTypeId.CMIS_FOLDER.value() + " = " + NodeType.NT_FOLDER,
        BaseTypeId.CMIS_DOCUMENT.value() + " = " + NodeType.NT_FILE};

    private ArrayList<Relation> list = new ArrayList<Relation>();

    public Nodes() {
        for (String aMap : map) {
            String[] tokens = aMap.split("=");
            list.add(new Relation(tokens[0].trim(), tokens[1].trim()));
        }
    }

    /**
     * Gets the name of the given property in JCR domain.
     * 
     * @param cmisName the name of the given property in CMIS domain.
     * @return the name of the given property in JCR domain.
     */
    public String findJcrName( String cmisName ) {
        for (Relation aList : list) {
            if (aList.cmisName.equals(cmisName)) {
                return aList.jcrName;
            }
        }
        return cmisName;
    }

    /**
     * Gets the name of the given property in CMIS domain.
     *
     * Ivan, Oleg, this method is better to make as not public or order to protect from incorrect Name conversion
     *
     * @param jcrName the name of the given property in JCR domain.
     * @return the name of the given property in CMIS domain.
     */
    protected String findCmisName( String jcrName ) {
        for (Relation aList : list) {
            if (aList.jcrName.equals(jcrName)) {
                return aList.cmisName;
            }
        }
        return jcrName;
    }

    /**
     * Gets the name of the given property in CMIS domain.
     *
     * @param jcrName the Name of the given property in JCR domain.
     * @return the name of the given property in CMIS domain.
     */
    public String findCmisName( Name jcrName ) {
        return findCmisName(getJcrTypeAsString(jcrName));
    }

    /*
    * add types mapping
    */
    public void addTypeMapping(Name jcrName, String cmisName) {
        String jcrStringName = jcrName.getString();
        for (Iterator<Relation> iterator = list.iterator(); iterator.hasNext(); ) {
            Relation aList = iterator.next();
            if (aList.jcrName.equals(jcrStringName)) {
                iterator.remove();
                break;
            }
        }
        list.add(new Relation(cmisName, getJcrTypeAsString(jcrName)));
    }

    /*
    * single place to make a correct string from jcr's Name
    */
    public String getJcrTypeAsString(Name jcrTypeName) {
        return jcrTypeName.toString();
    }


    private class Relation {
        private String jcrName;
        private String cmisName;

        private Relation( String cmisName,
                          String jcrName ) {
            this.cmisName = cmisName;
            this.jcrName = jcrName;
        }
    }

}
