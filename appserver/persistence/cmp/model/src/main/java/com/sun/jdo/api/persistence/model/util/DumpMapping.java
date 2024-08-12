/*
 * Copyright (c) 1997, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

/**
 * DumpMapping.java
 *
 */

package com.sun.jdo.api.persistence.model.util;

import com.sun.jdo.api.persistence.model.Model;
import com.sun.jdo.api.persistence.model.jdo.ConcurrencyGroupElement;
import com.sun.jdo.api.persistence.model.jdo.PersistenceClassElement;
import com.sun.jdo.api.persistence.model.jdo.PersistenceFieldElement;
import com.sun.jdo.api.persistence.model.jdo.RelationshipElement;
import com.sun.jdo.api.persistence.model.mapping.MappingClassElement;
import com.sun.jdo.api.persistence.model.mapping.MappingReferenceKeyElement;
import com.sun.jdo.api.persistence.model.mapping.MappingRelationshipElement;
import com.sun.jdo.api.persistence.model.mapping.impl.MappingClassElementImpl;
import com.sun.jdo.api.persistence.model.mapping.impl.MappingFieldElementImpl;
import com.sun.jdo.api.persistence.model.mapping.impl.MappingRelationshipElementImpl;
import com.sun.jdo.api.persistence.model.mapping.impl.MappingTableElementImpl;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

import org.netbeans.modules.dbschema.ColumnElement;
import org.netbeans.modules.dbschema.ColumnPairElement;

public class DumpMapping
{
    private static Model model;

    static
    {
        // initialize the model reference
        setModel(Model.RUNTIME);
    }

    /** Print out the cache of MappingClassElements to the specified PrintStream.
     * @param stream PrintStream used to dump the info
     */
    public static void dumpMappingCache (PrintStream stream)
    {
        stream.println("Mapping cache (class names -> MappingClassElements)"); // NOI18N
        for (Iterator i = model.getMappingCache().entrySet().iterator();
             i.hasNext();)
        {
            Map.Entry entry = (Map.Entry)i.next();
            String className = (String)entry.getKey();
            MappingClassElement mce =  (MappingClassElement)entry.getValue();
            String mceRepr = mce.getClass() + "@" + // NOI18N
                Integer.toHexString(System.identityHashCode(mce));
            stream.println("\t" + className + " ->\t" + mceRepr); //NOI18N
        }
    }

    /** Print out the cache of classLoaders to the specified PrintStream.
     * @param stream PrintStream used to dump the info
     */
    public static void dumpClassLoaderCache (PrintStream stream)
    {
        stream.println("ClassLoader cache (class names -> ClassLoaders)"); //NOI18N
        for (Iterator i = model.getClassLoaderCache().entrySet().iterator();
             i.hasNext();)
        {
            Map.Entry entry = (Map.Entry)i.next();
            String className = (String)entry.getKey();
            ClassLoader classLoader = (ClassLoader)entry.getValue();
            stream.println("\t" + className + " ->\t" + classLoader);  //NOI18N
        }
    }

    public static void main(String[] args)
    {
        for (int i = 0; i < args.length; i++)
        {
            String className = args[i];
            println(0, "\nClass " + className + ":");  //NOI18N

            try
            {
                MappingClassElementImpl mce = (MappingClassElementImpl)model.getMappingClass(className);
                if (mce != null)
                {
                    printPersistenceClassElement(mce.getPersistenceElement());
                    printMappingClassElement(mce);
                }
                else
                {
                    println(0, "Cannot find mapping info for class " + className + " (getMappingClass returns null)");  //NOI18N
                }
            }
            catch (Exception e)
            {
                println(0, "Problems during accessing mapping info for class " + className);  //NOI18N
                e.printStackTrace();
            }
        }
    }

    /** Sets the internal model reference used by the DumpMapping methods
     * to the specified Model instance.
     * @param newModel the Model instance to be used by DumpMapping
     */
    public static void setModel(Model newModel)
    {
        model = newModel;
    }

    // ----- JDO model ------

    public static void printPersistenceClassElement(PersistenceClassElement pce)
    {
        println(0, "\n--> PersistenceClassElement ");  //NOI18N
        println(1, "package  = " + pce.getPackage());  //NOI18N
        println(1, "name     = " + pce.getName());  //NOI18N
        println(1, "identity = " + getObjectIdentityTypeRepr(pce.getObjectIdentityType()));  //NOI18N
        println(1, "keyClass = " + pce.getKeyClass());  //NOI18N

        printPersistenceFieldElements(1, pce.getFields());
        printConcurrencyGroupElements(1, pce.getConcurrencyGroups());

        println(0, "<-- PersistenceClassElement\n ");  //NOI18N
    }


    public static void printPersistenceFieldElements(int tabs, PersistenceFieldElement[] fields)
    {
        if ((fields != null) && (fields.length > 0))
        {
            println(tabs, "--> fields ");  //NOI18N
            for (int i = 0; i < fields.length; i++)
            {
                PersistenceFieldElement pfe = fields[i];

                println(tabs, "[" + i + "] " + pfe.getClass());  //NOI18N
                println(tabs+1, "name             = " + pfe.getName());  //NOI18N
                println(tabs+1, "declaringClass   = " + pfe.getDeclaringClass());  //NOI18N
                println(tabs+1, "fieldNumber      = " + pfe.getFieldNumber());  //NOI18N
                println(tabs+1, "persistenceType  = " + getPersistenceTypeRepr(pfe.getPersistenceType()));  //NOI18N
                println(tabs+1, "read / write     = " + pfe.isReadSensitive() + " / " + pfe.isWriteSensitive());  //NOI18N
                println(tabs+1, "isKey            = " + pfe.isKey());  //NOI18N

                if (pfe instanceof RelationshipElement)
                {
                    RelationshipElement re = (RelationshipElement) pfe;

                    println(tabs+1, "bounds          = " + re.getLowerBound() + " / " +  re.getUpperBound());  //NOI18N
                    println(tabs+1, "deleteAction    = " + re.getDeleteAction());  //NOI18N
                    println(tabs+1, "updateAction    = " + re.getUpdateAction());  //NOI18N
                    println(tabs+1, "collectionClass = " + re.getCollectionClass());  //NOI18N
                    println(tabs+1, "elementClass     = " + re.getElementClass());  //NOI18N
                    println(tabs+1, "isPrefetch      = " + re.isPrefetch());  //NOI18N
                }
                printConcurrencyGroupElements(tabs+1, pfe.getConcurrencyGroups());
            }
            println(tabs, "<-- fields ");              //NOI18N
        }
    }

    public static void printConcurrencyGroupElements(int tabs, ConcurrencyGroupElement[] groups)
    {
        if ((groups != null) && (groups.length > 0))
        {
            println(tabs, "--> concurrency groups");  //NOI18N
            for (int i = 0; i < groups.length; i++)
            {
                ConcurrencyGroupElement cg = groups[i];
                println(tabs, "[" + i + "] " + cg.getClass());  //NOI18N
                println(tabs+1, "name           = " + cg.getName());  //NOI18N
                println(tabs+1, "declaringClass = " + cg.getDeclaringClass());  //NOI18N
            }
            println(tabs, "<-- concurrency groups");  //NOI18N
        }
    }

    // ----- Mapping model ------

    public static void printMappingClassElement(MappingClassElement mce)
    {
        println(0, "\n--> MappingClassElement");  //NOI18N

        println(1, "databaseRoot = " + mce.getDatabaseRoot());  //NOI18N
        printMappingTableElements(1, mce.getTables());
        printMappingFieldElements(1, mce.getFields());

        println(0, "<-- MappingClassElement");  //NOI18N
    }

    public static void printMappingTableElements(int tabs, ArrayList tables)
    {
        final int count = ((tables != null) ? tables.size() : 0);

        if (count > 0)
        {
            println(tabs, "--> tables ");  //NOI18N
            for (int i = 0; i < count; i++)
            {
                MappingTableElementImpl mte = (MappingTableElementImpl) tables.get(i);

                println(tabs, "[" + i + "] " + mte.getClass());  //NOI18N

                println(tabs+1, "table           = " + mte.getTable());  //NOI18N
                println(tabs+1, "tableObject     = " + mte.getTableObject());  //NOI18N
                println(tabs+1, "key             = " + mte.getKey());  //NOI18N
                println(tabs+1, "keyObjects      = " + mte.getKeyObjects());  //NOI18N
                printMappingRefKeyElements(tabs+1, mte.getReferencingKeys());
            }
            println(tabs, "<-- tables ");  //NOI18N
        }
     }

    public static void printMappingRefKeyElements(int tabs, ArrayList refKeys)
    {
        final int count = ((refKeys != null) ? refKeys.size() : 0);

        if (count > 0)
        {
            println(tabs, "--> tables ");  //NOI18N
            for (int i = 0; i < count; i++)
            {
                MappingReferenceKeyElement mrke = (MappingReferenceKeyElement)refKeys.get(i);

                println(tabs, "[" + i + "] " + mrke.getClass());  //NOI18N

                println(tabs+1, "table           = " + mrke.getDeclaringTable());  //NOI18N
                println(tabs+1, "pairs           = " + mrke.getColumnPairNames());  //NOI18N
            }
            println(tabs, "<-- tables ");  //NOI18N
        }
     }

     public static void printMappingFieldElements(int tabs, ArrayList fields)
    {
        final int count = ((fields != null) ? fields.size() : 0);

        if (count > 0)
        {
            println(tabs, "--> fields ");  //NOI18N
            for (int i = 0; i < count; i++)
            {
                MappingFieldElementImpl mfe = (MappingFieldElementImpl) fields.get(i);

                println(tabs, "[" + i + "] " + mfe.getClass());  //NOI18N
                println(tabs+1, "name            = " + mfe.getName());  //NOI18N
                println(tabs+1, "fetchGroup      = " + mfe.getFetchGroup());  //NOI18N
                println(tabs+1, "columns         = " + mfe.getColumns());  //NOI18N

                if (!(mfe instanceof MappingRelationshipElement))
                {
                    println(tabs+1, "columnObjects     = " + mfe.getColumnObjects());  //NOI18N
                }
                else
                {
                    MappingRelationshipElementImpl mre = (MappingRelationshipElementImpl) mfe;

                    ArrayList columnObjects = mre.getColumnObjects();
                    int colCount =
                        ((columnObjects != null) ? columnObjects.size() : 0);
                    if (colCount > 0)
                    {
                        println(tabs+1, "--> columnsObjects ");  //NOI18N
                        for (int j = 0; j < colCount; j++)
                        {
                            ColumnPairElement fce = (ColumnPairElement) columnObjects.get(j);
                            ColumnElement rce = (fce!=null)?fce.getReferencedColumn():null;
                            println(tabs+1, "[" + j + "] " + fce + " -> " + rce);  //NOI18N
                        }
                        println(tabs+1, "<-- columnsObjects ");  //NOI18N
                    }

                    println(tabs+1, "associatedColumns = " + mre.getAssociatedColumns());  //NOI18N

                    ArrayList associatedColumnObjects = mre.getAssociatedColumnObjects();
                    colCount = ((associatedColumnObjects != null) ?
                        associatedColumnObjects.size() : 0);
                    if (colCount > 0)
                    {
                        println(tabs+1, "--> associatedColumnObjects ");  //NOI18N
                        for (int j = 0; j < colCount; j++)
                        {
                            ColumnPairElement fce = (ColumnPairElement) associatedColumnObjects.get(j);
                            ColumnElement rce = (fce!=null)?fce.getReferencedColumn():null;
                            println(tabs+1, "[" + j + "] " + fce + " -> " + rce);  //NOI18N
                        }
                        println(tabs+1, "<-- associatedColumnObjects ");  //NOI18N
                    }
                }
            }
            println(tabs, "<-- fields ");  //NOI18N
        }

    }

   // ----- helper methods -----

    static String getObjectIdentityTypeRepr(int objectIdentityType)
    {
        switch (objectIdentityType)
        {
        case PersistenceClassElement.APPLICATION_IDENTITY:
            return "APPLICATION_IDENTITY";  //NOI18N
        case PersistenceClassElement.DATABASE_IDENTITY:
            return "DATABASE_IDENTITY_IDENTITY";  //NOI18N
        case PersistenceClassElement.UNMANAGED_IDENTITY:
            return "UNMANAGED_IDENTITY";  //NOI18N
        default:
            return "UNKNOWN";  //NOI18N
        }
    }

    static String getPersistenceTypeRepr(int persistenceType)
    {
        switch (persistenceType)
        {
        case PersistenceFieldElement.PERSISTENT:
            return "PERSISTENT";  //NOI18N
        case PersistenceFieldElement.DERIVED:
            return "DERIVED";  //NOI18N
        case PersistenceFieldElement.TRANSIENT:
            return "TRANSIENT";  //NOI18N
        default:
            return "UNKNOWN";  //NOI18N
        }
    }

    static void println(int indent, String text)
    {
        for (int i = 0; i < indent; i++)
        {
            System.out.print("\t");  //NOI18N
        }

        System.out.println(text);
    }
}
