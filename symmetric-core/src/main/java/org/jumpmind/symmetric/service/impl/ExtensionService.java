/**
 * Licensed to JumpMind Inc under one or more contributor
 * license agreements.  See the NOTICE file distributed
 * with this work for additional information regarding
 * copyright ownership.  JumpMind Inc licenses this file
 * to you under the GNU General Public License, version 3.0 (GPLv3)
 * (the "License"); you may not use this file except in compliance
 * with the License.
 *
 * You should have received a copy of the GNU General Public License,
 * version 3.0 (GPLv3) along with this library; if not, see
 * <http://www.gnu.org/licenses/>.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jumpmind.symmetric.service.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.ClassUtils;
import org.jumpmind.db.sql.ISqlRowMapper;
import org.jumpmind.db.sql.Row;
import org.jumpmind.extension.IBuiltInExtensionPoint;
import org.jumpmind.extension.IExtensionPoint;
import org.jumpmind.symmetric.ISymmetricEngine;
import org.jumpmind.symmetric.common.ParameterConstants;
import org.jumpmind.symmetric.common.TableConstants;
import org.jumpmind.symmetric.ext.ExtensionPointMetaData;
import org.jumpmind.symmetric.ext.INodeGroupExtensionPoint;
import org.jumpmind.symmetric.ext.ISymmetricEngineAware;
import org.jumpmind.symmetric.model.Extension;
import org.jumpmind.symmetric.service.IExtensionService;
import org.jumpmind.util.SimpleClassCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This service registers {@link IExtensionPoint}s defined both by SymmetricDS
 * and others found in the {@link ApplicationContext}.
 * It also reads the sym_extension table for {@link IExtensionPoint}s defined there.
 * <P>
 * SymmetricDS reads in any Spring XML file found in the classpath of the
 * application that matches the following pattern:
 * /META-INF/services/symmetric-*-ext.xml
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class ExtensionService extends AbstractService implements IExtensionService {

    private final static Logger log = LoggerFactory.getLogger(ExtensionService.class);

    protected ISymmetricEngine engine;
   
    protected Map<Class, Map<String, IExtensionPoint>> staticExtensionsByClassByName = new HashMap<Class, Map<String, IExtensionPoint>>();
    
    protected Map<Class, Map<String, IExtensionPoint>> extensionsByClassByName;
    
    protected List<ExtensionPointMetaData> extensionMetaData;

    public ExtensionService(ISymmetricEngine engine) {
        super(engine.getParameterService(), engine.getSymmetricDialect());
        this.engine = engine;
        setSqlMap(new ExtensionServiceSqlMap(symmetricDialect.getPlatform(), createSqlReplacementTokens()));
    }

    public void refresh() {
        extensionsByClassByName = new HashMap<Class, Map<String, IExtensionPoint>>();
        extensionMetaData = new ArrayList<ExtensionPointMetaData>();

        for (Class extensionClass : staticExtensionsByClassByName.keySet()) {
            Map<String, IExtensionPoint> byNameMap = staticExtensionsByClassByName.get(extensionClass);
            for (String name : byNameMap.keySet()) {
                IExtensionPoint ext = byNameMap.get(name);
                getExtensionsByNameMap(extensionClass).put(name, ext);
                extensionMetaData.add(new ExtensionPointMetaData(ext, name, extensionClass, true));        
            }
        }

        String prefix = parameterService.getString(ParameterConstants.RUNTIME_CONFIG_TABLE_PREFIX);
        if (platform.getTableFromCache(TableConstants.getTableName(prefix, TableConstants.SYM_EXTENSION), false) != null) {
            List<Extension> extensionList = sqlTemplate.query(getSql("selectAll"), new ExtensionRowMapper(), parameterService.getNodeGroupId());
            log.info("Found {} extension points from the database that will be registered", extensionList.size());
    
            for (Extension extension : extensionList) {
                if (extension.getExtensionType().equalsIgnoreCase(Extension.EXTENSION_TYPE_JAVA)) {
                    try {
                        Object ext = SimpleClassCompiler.getInstance().getCompiledClass(extension.getExtensionText());
                        if (ext instanceof IExtensionPoint) {
                            registerExtension(extension.getExtensionId(), (IExtensionPoint) ext);
                        } else {
                            log.error("Missing IExtensionPoint interface for extension " + extension.getExtensionId());
                        }
                    } catch (Exception e) {
                        log.error("Error while compiling extension " + extension.getExtensionId(), e);
                    }
                } else if (extension.getExtensionType().equalsIgnoreCase(Extension.EXTENSION_TYPE_BSH)) {
                    // TODO: implement BSH wrapper interface
                } else {
                    log.error("Skipping extension " + extension.getExtensionId() + ", unknown extension type " + extension.getExtensionType());
                }
            }
        }
    }

    protected boolean registerExtension(String name, IExtensionPoint ext) {
        return registerExtension(name, ext, true);
    }
    
    protected boolean registerExtension(String name, IExtensionPoint ext, boolean shouldLog) {
        boolean installed = false;
        if (initializeExtension(ext)) {
            for (Class extensionClass : getExtensionClassList(ext)) {
                if (ext instanceof IBuiltInExtensionPoint) {
                    log.debug("Registering built-in extension named '{}' of type '{}'", name, extensionClass.getSimpleName());
                } else if (shouldLog) {
                    log.info("Registering extension named '{}' of type '{}'", name, extensionClass.getSimpleName());
                }
                installed = true;
                extensionMetaData.add(new ExtensionPointMetaData(ext, name, extensionClass, true));
                getExtensionsByNameMap(extensionClass).put(name, ext);
            }
    
            if (!installed) {
                extensionMetaData.add(new ExtensionPointMetaData(ext, name, null, false));
            }
        }
        return installed;
    }
    
    protected void unRegisterExtension(String name, IExtensionPoint ext) {       
        for (Class extensionClass : getExtensionClassList(ext)) {
            getExtensionsByNameMap(extensionClass).remove(name);
        }
        Iterator<ExtensionPointMetaData> iterator = extensionMetaData.iterator();
        while (iterator.hasNext()) {
            ExtensionPointMetaData metaData = iterator.next();
            if (metaData.getExtensionPoint() == ext && metaData.getName().equals(name)) {
                iterator.remove();
            }
        }
    }
    
    protected boolean initializeExtension(IExtensionPoint ext) {
        boolean shouldInstall = false;
        if (ext instanceof ISymmetricEngineAware) {
            ((ISymmetricEngineAware) ext).setSymmetricEngine(engine);
        }

        if (ext instanceof INodeGroupExtensionPoint) {
            String nodeGroupId = parameterService.getNodeGroupId();
            INodeGroupExtensionPoint nodeExt = (INodeGroupExtensionPoint) ext;
            String[] ids = nodeExt.getNodeGroupIdsToApplyTo();
            if (ids != null) {
                for (String targetNodeGroupId : ids) {
                    if (nodeGroupId.equals(targetNodeGroupId)) {
                        shouldInstall = true;
                    }
                }
            } else {
                shouldInstall = true;
            }
        } else {
            shouldInstall = true;
        }
        return shouldInstall;
    }

    protected List<Class> getExtensionClassList(IExtensionPoint ext) {
        List<Class> classList = new ArrayList<Class>();
        List<Class> interfaces = ClassUtils.getAllInterfaces(ext.getClass());
        for (Class clazz : interfaces) {
            if (IExtensionPoint.class.isAssignableFrom(clazz) && ! clazz.getName().equals(IExtensionPoint.class.getName())) {
                classList.add(clazz);
            }
        }
        return classList;
    }

    public List<ExtensionPointMetaData> getExtensionPointMetaData() {
        return new ArrayList<ExtensionPointMetaData>(extensionMetaData);
    }

    public <T extends IExtensionPoint> T getExtensionPoint(Class<T> extensionClass) {
        for (T extension : getExtensionPointList(extensionClass)) {
            return extension;
        }
        return null;
    }

    public <T extends IExtensionPoint> List<T> getExtensionPointList(Class<T> extensionClass) {
        return new ArrayList<T>(getExtensionPointMap(extensionClass).values());
    }

    public <T extends IExtensionPoint> Map<String, T> getExtensionPointMap(Class<T> extensionClass) {
        return (Map<String, T>) getExtensionsByNameMap(extensionClass);
    }

    public void addExtensionPoint(IExtensionPoint extension) {
        for (Class extensionClass : getExtensionClassList(extension)) {
            getStaticExtensionsByNameMap(extensionClass).put(extension.getClass().getCanonicalName(), extension);
        }
        registerExtension(extension.getClass().getCanonicalName(), extension, false);
    }

    public void addExtensionPoint(String name, IExtensionPoint extension) {
        for (Class extensionClass : getExtensionClassList(extension)) {
            getStaticExtensionsByNameMap(extensionClass).put(name, extension);
        }
        registerExtension(name, extension, false);
    }

    public void removeExtensionPoint(IExtensionPoint extension) {
        for (Class extensionClass : getExtensionClassList(extension)) {
            getStaticExtensionsByNameMap(extensionClass).remove(extension.getClass().getCanonicalName());
        }
        unRegisterExtension(extension.getClass().getCanonicalName(), extension);
    }

    protected Map<String, IExtensionPoint> getStaticExtensionsByNameMap(Class extensionClass) {
        return getExtensionsByNameMap(staticExtensionsByClassByName, extensionClass);
    }

    protected Map<String, IExtensionPoint> getExtensionsByNameMap(Class extensionClass) {
        return getExtensionsByNameMap(extensionsByClassByName, extensionClass);
    }

    protected Map<String, IExtensionPoint> getExtensionsByNameMap(Map<Class, Map<String, IExtensionPoint>> byClassByNameMap,
            Class extensionClass) {
        Map<String, IExtensionPoint> byNameMap = byClassByNameMap.get(extensionClass);
        if (byNameMap == null) {
            byNameMap = new HashMap<String, IExtensionPoint>();
            byClassByNameMap.put(extensionClass, byNameMap);
        }
        return byNameMap;
    }

    class ExtensionRowMapper implements ISqlRowMapper<Extension> {
        @Override
        public Extension mapRow(Row row) {
            Extension extension = new Extension();
            extension.setExtensionId(row.getString("extension_id"));
            extension.setExtensionType(row.getString("extension_type"));
            extension.setInterfaceName(row.getString("interface_name"));
            extension.setNodeGroupId(row.getString("node_group_id"));
            extension.setEnabled(row.getBoolean("enabled"));
            extension.setExtensionOrder(row.getInt("extension_order"));
            extension.setExtensionText(row.getString("extension_text"));
            extension.setCreateTime(row.getDateTime("create_time"));
            extension.setLastUpdateBy(row.getString("last_update_by"));
            extension.setLastUpdateTime(row.getDateTime("last_update_time"));
            return extension;
        }
    }
}
