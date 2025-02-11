/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.admin.service.impl;

import org.apache.dubbo.admin.common.exception.ResourceNotFoundException;
import org.apache.dubbo.admin.common.util.Constants;
import org.apache.dubbo.admin.common.util.ConvertUtil;
import org.apache.dubbo.admin.common.util.RouteUtils;
import org.apache.dubbo.admin.common.util.YamlParser;
import org.apache.dubbo.admin.model.domain.Route;
import org.apache.dubbo.admin.model.dto.AccessDTO;
import org.apache.dubbo.admin.model.dto.BaseDTO;
import org.apache.dubbo.admin.model.dto.ConditionRouteDTO;
import org.apache.dubbo.admin.model.dto.TagRouteDTO;
import org.apache.dubbo.admin.model.store.RoutingRule;
import org.apache.dubbo.admin.model.store.TagRoute;
import org.apache.dubbo.admin.service.RouteService;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.common.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedList;
import java.util.List;

@Component
public class RouteServiceImpl extends AbstractService implements RouteService {

    public static String getScopeFromDTO(BaseDTO baseDTO) {
        if (StringUtils.isNotEmpty(baseDTO.getApplication())) {
            return org.apache.dubbo.admin.common.util.Constants.APPLICATION;
        } else {
            return org.apache.dubbo.admin.common.util.Constants.SERVICE;
        }
    }
    //getIdFromDTO(conditionRoute);
    @Override
    public void createConditionRoute(ConditionRouteDTO conditionRoute) {
        String id = RouteServiceImpl.getScopeFromDTO(conditionRoute);
        String path = getPath(id, Constants.CONDITION_ROUTE);
        String existConfig = dynamicConfiguration.getConfig(path);
        RoutingRule existRule = null;
        if (existConfig != null) {
            existRule = YamlParser.loadObject(existConfig, RoutingRule.class);
        }
        existRule = RouteUtils.insertConditionRule(existRule, conditionRoute);
        //register2.7
        dynamicConfiguration.setConfig(path, YamlParser.dumpObject(existRule));

        //register2.6
        if (StringUtils.isNotEmpty(conditionRoute.getService())) {
            for (Route old : convertRouteToOldRoute(conditionRoute)) {
            	registry.register(old.toUrl().addParameter(Constants.COMPATIBLE_CONFIG, true));
            }
        }

    }

    @Override
    public void updateConditionRoute(ConditionRouteDTO newConditionRoute) {
        String id = RouteServiceImpl.getScopeFromDTO(newConditionRoute);;
        String path = getPath(id, Constants.CONDITION_ROUTE);
        String existConfig = dynamicConfiguration.getConfig(path);
        if (existConfig == null) {
            throw new ResourceNotFoundException("no existing condition route for path: " + path);
        }
        RoutingRule routingRule = YamlParser.loadObject(existConfig, RoutingRule.class);
        ConditionRouteDTO oldConditionRoute = RouteUtils.createConditionRouteFromRule(routingRule);
        routingRule = RouteUtils.insertConditionRule(routingRule, newConditionRoute);
        dynamicConfiguration.setConfig(path, YamlParser.dumpObject(routingRule));

        //for 2.6
        if (StringUtils.isNotEmpty(newConditionRoute.getService())) {
            for (Route old : convertRouteToOldRoute(oldConditionRoute)) {
                old.setService(id);
            	registry.unregister(old.toUrl().addParameter(Constants.COMPATIBLE_CONFIG, true));
            }
            for (Route updated : convertRouteToOldRoute(newConditionRoute)) {
            	registry.register(updated.toUrl().addParameter(Constants.COMPATIBLE_CONFIG, true));
            }
        }
    }

    @Override
    public void deleteConditionRoute(String id) {
        String path = getPath(id, Constants.CONDITION_ROUTE);

        String config = dynamicConfiguration.getConfig(path);
        if (config == null) {
            //throw exception
        }
        RoutingRule route = YamlParser.loadObject(config, RoutingRule.class);
        dynamicConfiguration.deleteConfig(path);

        //for 2.6
        if (Constants.SERVICE.equals(route.getScope())) {
            RoutingRule originRule = YamlParser.loadObject(config, RoutingRule.class);
            ConditionRouteDTO conditionRouteDTO = RouteUtils.createConditionRouteFromRule(originRule);
            for (Route old : convertRouteToOldRoute(conditionRouteDTO)) {
                old.setService(id);
                URL oldUrl = old.toUrl();
                if(oldUrl.getParameter("rule").contains("host") && oldUrl.getParameter("rule").contains("false")) {
                    registry.unregister(oldUrl);
                } else {
                    registry.unregister(oldUrl.addParameter(Constants.COMPATIBLE_CONFIG, true));
                }
            }
        }
    }

    @Override
    public void deleteAccess(String id) {
        String path = getPath(id, Constants.CONDITION_ROUTE);
        String config = dynamicConfiguration.getConfig(path);
        if (config != null) {
            RoutingRule ruleDTO = YamlParser.loadObject(config, RoutingRule.class);
            List<String> blackWhiteList = RouteUtils.filterBlackWhiteListFromConditions(ruleDTO.getConditions());
            List<String> conditions = RouteUtils.removeBlackWhiteListRuleFromConditions(ruleDTO.getConditions());
            if (conditions.size() == 0) {
                dynamicConfiguration.deleteConfig(path);
            } else {
                ruleDTO.setConditions(conditions);
                dynamicConfiguration.setConfig(path, YamlParser.dumpObject(ruleDTO));
            }
            //2.6
            if (Constants.SERVICE.equals(ruleDTO.getScope()) && blackWhiteList.size() > 0) {
                Route route = RouteUtils.convertBlackWhiteListtoRoute(blackWhiteList, Constants.SERVICE, id);
                registry.unregister(route.toUrl());
            }
        }
    }

    @Override
    public void createAccess(AccessDTO accessDTO) {
        String id = RouteServiceImpl.getScopeFromDTO(accessDTO);
        String path = getPath(id, Constants.CONDITION_ROUTE);
        String config = dynamicConfiguration.getConfig(path);
        List<String> blackWhiteList = RouteUtils.convertToBlackWhiteList(accessDTO);
        RoutingRule ruleDTO;
        if (config == null) {
            ruleDTO = new RoutingRule();
            ruleDTO.setEnabled(true);
            if (StringUtils.isNoneEmpty(accessDTO.getApplication())) {
                ruleDTO.setKey(accessDTO.getApplication());
                ruleDTO.setScope(Constants.APPLICATION);
            } else {
                ruleDTO.setKey(accessDTO.getService().replace("/", "*"));
                ruleDTO.setScope(Constants.SERVICE);
            }
            ruleDTO.setConditions(blackWhiteList);
        } else {
            ruleDTO = YamlParser.loadObject(config, RoutingRule.class);
            if (ruleDTO.getConditions() == null) {
                ruleDTO.setConditions(blackWhiteList);
            } else {
                ruleDTO.getConditions().addAll(blackWhiteList);
            }
        }
        dynamicConfiguration.setConfig(path, YamlParser.dumpObject(ruleDTO));

        //for 2.6
        if (ruleDTO.getScope().equals("service")) {
            Route route = RouteUtils.convertAccessDTOtoRoute(accessDTO);
            registry.register(route.toUrl());
        }

    }

    @Override
    public AccessDTO findAccess(String id) {
        String path = getPath(id, Constants.CONDITION_ROUTE);
        String config = dynamicConfiguration.getConfig(path);
        if (config != null) {
            RoutingRule ruleDTO = YamlParser.loadObject(config, RoutingRule.class);
            List<String> blackWhiteList = RouteUtils.filterBlackWhiteListFromConditions(ruleDTO.getConditions());
            if(CollectionUtils.isNotEmpty(blackWhiteList)) {
                AccessDTO accessDTO = RouteUtils.convertToAccessDTO(blackWhiteList, ruleDTO.getScope(), ruleDTO.getKey());
                accessDTO.setId(id);
                if (Constants.SERVICE.equals(ruleDTO.getScope())) {
                    ConvertUtil.detachIdToService(id, accessDTO);
                }
                return accessDTO;
            }
        }
        return null;
    }

    @Override
    public void updateAccess(AccessDTO accessDTO) {
        String key = RouteServiceImpl.getScopeFromDTO(accessDTO);
        String path = getPath(key, Constants.CONDITION_ROUTE);
        List<String> blackWhiteList = RouteUtils.convertToBlackWhiteList(accessDTO);
        String config = dynamicConfiguration.getConfig(path);
        List<String> oldList = null;
        if (config != null) {
            RoutingRule ruleDTO = YamlParser.loadObject(config, RoutingRule.class);
            oldList = RouteUtils.filterBlackWhiteListFromConditions(ruleDTO.getConditions());
            List<String> conditions = RouteUtils.filterConditionsExcludeBlackWhiteList(ruleDTO.getConditions());
            conditions.addAll(blackWhiteList);
            ruleDTO.setConditions(conditions);
            dynamicConfiguration.setConfig(path, YamlParser.dumpObject(ruleDTO));
        }

        //2.6
        if (StringUtils.isNotEmpty(accessDTO.getService())) {
            Route oldRoute = RouteUtils.convertBlackWhiteListtoRoute(oldList, Constants.SERVICE, key);
            Route newRoute = RouteUtils.convertAccessDTOtoRoute(accessDTO);
            registry.unregister(oldRoute.toUrl());
            registry.register(newRoute.toUrl());
        }
    }

    @Override
    public void enableConditionRoute(String id) {
        String path = getPath(id, Constants.CONDITION_ROUTE);

        String config = dynamicConfiguration.getConfig(path);
        if (config != null) {
            RoutingRule ruleDTO = YamlParser.loadObject(config, RoutingRule.class);

            if (Constants.SERVICE.equals(ruleDTO.getScope())) {
                //for2.6
                for (Route oldRoute : convertRouteToOldRoute(RouteUtils.createConditionRouteFromRule(ruleDTO))) {
                    URL oldURL = oldRoute.toUrl();
                    registry.unregister(oldURL);
                    oldURL = oldURL.addParameter("enabled", true);
                    registry.register(oldURL);
                }
            }

            //2.7
            ruleDTO.setEnabled(true);
            dynamicConfiguration.setConfig(path, YamlParser.dumpObject(ruleDTO));
        }

    }

    @Override
    public void disableConditionRoute(String id) {
        String path = getPath(id, Constants.CONDITION_ROUTE);

        String config = dynamicConfiguration.getConfig(path);
        if (config != null) {
            RoutingRule routeRule = YamlParser.loadObject(config, RoutingRule.class);

            if (Constants.SERVICE.equals(routeRule.getScope())) {
                //for 2.6
            	for (Route oldRoute : convertRouteToOldRoute(RouteUtils.createConditionRouteFromRule(routeRule))) {
	                URL oldURL = oldRoute.toUrl();
	                registry.unregister(oldURL);
	                oldURL = oldURL.addParameter("enabled", false);
	                registry.register(oldURL);
            	}
            }

            //2.7
            routeRule.setEnabled(false);
            dynamicConfiguration.setConfig(path, YamlParser.dumpObject(routeRule));
        }

    }

    @Override
    public ConditionRouteDTO findConditionRoute(ConditionRouteDTO crDTO) {
        return findConditionRoute(RouteServiceImpl.getScopeFromDTO(crDTO));
    }

    @Override
    public ConditionRouteDTO findConditionRoute(String id) {
        String path = getPath(id, Constants.CONDITION_ROUTE);
        String config = dynamicConfiguration.getConfig(path);
        if (config != null) {
            RoutingRule routingRule = YamlParser.loadObject(config, RoutingRule.class);
            ConditionRouteDTO conditionRouteDTO = RouteUtils.createConditionRouteFromRule(routingRule);
            if(null == conditionRouteDTO || CollectionUtils.isEmpty(conditionRouteDTO.getConditions())) {
                return null;
            }
            String service = conditionRouteDTO.getService();
            if (org.apache.commons.lang3.StringUtils.isNotBlank(service)) {
                conditionRouteDTO.setService(service.replace("*", "/"));
            }
            String[] detachResult = ConvertUtil.detachId(id);
            if (detachResult.length > 1) {
                conditionRouteDTO.setServiceVersion(detachResult[1]);
            }
            if (detachResult.length > 2) {
                conditionRouteDTO.setServiceGroup(detachResult[2]);
            }
            conditionRouteDTO.setId(id);
            return conditionRouteDTO;
        }
        return null;
    }

    @Override
    public void createTagRoute(TagRouteDTO tagRoute) {
        String id =RouteServiceImpl.getScopeFromDTO(tagRoute);
        String path = getPath(id,Constants.TAG_ROUTE);
        TagRoute store = RouteUtils.convertTagroutetoStore(tagRoute);
        dynamicConfiguration.setConfig(path, YamlParser.dumpObject(store));
    }

    @Override
    public void updateTagRoute(TagRouteDTO tagRoute) {
        String id = RouteServiceImpl.getScopeFromDTO(tagRoute);
        String path = getPath(id, Constants.TAG_ROUTE);
        if (dynamicConfiguration.getConfig(path) == null) {
            throw new ResourceNotFoundException("can not find tagroute: " + id);
            //throw exception
        }
        TagRoute store = RouteUtils.convertTagroutetoStore(tagRoute);
        dynamicConfiguration.setConfig(path, YamlParser.dumpObject(store));

    }

    @Override
    public void deleteTagRoute(String id) {
        String path = getPath(id, Constants.TAG_ROUTE);
        dynamicConfiguration.deleteConfig(path);
    }

    @Override
    public void enableTagRoute(String id) {
        String path = getPath(id, Constants.TAG_ROUTE);
        String config = dynamicConfiguration.getConfig(path);
        if (config != null) {
            TagRoute tagRoute = YamlParser.loadObject(config, TagRoute.class);
            tagRoute.setEnabled(true);
            dynamicConfiguration.setConfig(path, YamlParser.dumpObject(tagRoute));
        }

    }

    @Override
    public void disableTagRoute(String id) {
        String path = getPath(id, Constants.TAG_ROUTE);
        String config = dynamicConfiguration.getConfig(path);
        if (config != null) {
            TagRoute tagRoute = YamlParser.loadObject(config, TagRoute.class);
            tagRoute.setEnabled(false);
            dynamicConfiguration.setConfig(path, YamlParser.dumpObject(tagRoute));
        }

    }

    @Override
    public TagRouteDTO findTagRoute(String id) {
        String path = getPath(id, Constants.TAG_ROUTE);
        String config = dynamicConfiguration.getConfig(path);
        if (config != null) {
            TagRoute tagRoute = YamlParser.loadObject(config, TagRoute.class);
            return RouteUtils.convertTagroutetoDisplay(tagRoute);
        }
        return null;
    }

    private String getPath(String key, String type) {
        key = key.replace("/", "*");
        if (type.equals(Constants.CONDITION_ROUTE)) {
            return key + Constants.CONDITION_RULE_SUFFIX;
        } else {
            return key + Constants.TAG_RULE_SUFFIX;
        }
    }

    private String parseCondition(String condition) {
        StringBuilder when = new StringBuilder();
        StringBuilder then = new StringBuilder();
        condition = condition.trim();
        if (condition.contains("=>")) {
            String[] array = condition.split("=>", 2);
            String consumer = array[0].trim();
            String provider = array[1].trim();
            if (consumer.length() != 0) {
                if (when.length() != 0) {
                    when.append(" & ").append(consumer);
                } else {
                    when.append(consumer);
                }
            }
            if (provider.length() != 0) {
                if (then.length() != 0) {
                    then.append(" & ").append(provider);
                } else {
                    then.append(provider);
                }
            }
        }
        return (when.append(" => ").append(then)).toString();
    }

    private List<Route> convertRouteToOldRoute(ConditionRouteDTO route) {
    	List<Route> oldList = new LinkedList<Route>();
    	for (String condition : route.getConditions()) {
	        Route old = new Route();
            old.setService(RouteServiceImpl.getScopeFromDTO(route));
	        old.setEnabled(route.isEnabled());
	        old.setForce(route.isForce());
	        old.setRuntime(route.isRuntime());
	        old.setPriority(route.getPriority());
	        String rule = parseCondition(condition);
	        old.setRule(rule);
	        oldList.add(old);
    	}
        return oldList;
    }
}
