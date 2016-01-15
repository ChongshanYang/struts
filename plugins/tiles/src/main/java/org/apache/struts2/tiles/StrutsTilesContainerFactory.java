/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.struts2.tiles;

import ognl.OgnlException;
import ognl.OgnlRuntime;
import ognl.PropertyAccessor;
import org.apache.tiles.TilesContainer;
import org.apache.tiles.definition.DefinitionsFactory;
import org.apache.tiles.definition.pattern.DefinitionPatternMatcherFactory;
import org.apache.tiles.definition.pattern.PatternDefinitionResolver;
import org.apache.tiles.definition.pattern.PrefixedPatternDefinitionResolver;
import org.apache.tiles.definition.pattern.regexp.RegexpDefinitionPatternMatcherFactory;
import org.apache.tiles.definition.pattern.wildcard.WildcardDefinitionPatternMatcherFactory;
import org.apache.tiles.el.ELAttributeEvaluator;
import org.apache.tiles.el.JspExpressionFactoryFactory;
import org.apache.tiles.el.ScopeELResolver;
import org.apache.tiles.el.TilesContextBeanELResolver;
import org.apache.tiles.el.TilesContextELResolver;
import org.apache.tiles.evaluator.AttributeEvaluatorFactory;
import org.apache.tiles.evaluator.BasicAttributeEvaluatorFactory;
import org.apache.tiles.evaluator.impl.DirectAttributeEvaluator;
import org.apache.tiles.factory.BasicTilesContainerFactory;
import org.apache.tiles.factory.TilesContainerFactoryException;
import org.apache.tiles.impl.mgmt.CachingTilesContainer;
import org.apache.tiles.locale.LocaleResolver;
import org.apache.tiles.ognl.AnyScopePropertyAccessor;
import org.apache.tiles.ognl.DelegatePropertyAccessor;
import org.apache.tiles.ognl.NestedObjectDelegatePropertyAccessor;
import org.apache.tiles.ognl.OGNLAttributeEvaluator;
import org.apache.tiles.ognl.PropertyAccessorDelegateFactory;
import org.apache.tiles.ognl.ScopePropertyAccessor;
import org.apache.tiles.ognl.TilesApplicationContextNestedObjectExtractor;
import org.apache.tiles.ognl.TilesContextPropertyAccessorDelegateFactory;
import org.apache.tiles.request.ApplicationContext;
import org.apache.tiles.request.ApplicationResource;
import org.apache.tiles.request.Request;
import org.apache.tiles.request.render.BasicRendererFactory;
import org.apache.tiles.request.render.ChainedDelegateRenderer;
import org.apache.tiles.request.render.Renderer;

import javax.el.ArrayELResolver;
import javax.el.BeanELResolver;
import javax.el.CompositeELResolver;
import javax.el.ELResolver;
import javax.el.ListELResolver;
import javax.el.MapELResolver;
import javax.el.ResourceBundleELResolver;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dedicated Struts factory to build Tiles container with support for:
 * - Freemarker
 * - OGNL (as default)
 * - EL
 * - Wildcards
 *
 * If you need additional features create your own listener and factory,
 * you can base on code from Tiles' CompleteAutoloadTilesContainerFactory
 */
public class StrutsTilesContainerFactory extends BasicTilesContainerFactory {

    /**
     * The freemarker renderer name.
     */
    public static final String FREEMARKER_RENDERER_NAME = "freemarker";

    /**
     * Supported pattern types
     */
    public static final String PATTERN_WILDCARD = "WILDCARD";
    public static final String PATTERN_REGEXP = "REGEXP";

    /**
     * Default pattern to be used to collect Tiles definitions if user didn't configure any
     */
    public static final String TILES_DEFAULT_PATTERN = "tiles*.xml";

    @Override
    public TilesContainer createDecoratedContainer(TilesContainer originalContainer, ApplicationContext applicationContext) {
        return new CachingTilesContainer(originalContainer);
    }

    @Override
    protected void registerAttributeRenderers(
            final BasicRendererFactory rendererFactory,
            final ApplicationContext applicationContext,
            final TilesContainer container,
            final AttributeEvaluatorFactory attributeEvaluatorFactory) {

        super.registerAttributeRenderers(rendererFactory, applicationContext, container, attributeEvaluatorFactory);

        StrutsFreeMarkerAttributeRenderer freemarkerRenderer = new StrutsFreeMarkerAttributeRenderer();

        rendererFactory.registerRenderer(FREEMARKER_RENDERER_NAME, freemarkerRenderer);
    }

    @Override
    protected Renderer createDefaultAttributeRenderer(
            BasicRendererFactory rendererFactory,
            ApplicationContext applicationContext,
            TilesContainer container,
            AttributeEvaluatorFactory attributeEvaluatorFactory) {

        ChainedDelegateRenderer retValue = new ChainedDelegateRenderer();
        retValue.addAttributeRenderer(rendererFactory.getRenderer(DEFINITION_RENDERER_NAME));
        retValue.addAttributeRenderer(rendererFactory.getRenderer(FREEMARKER_RENDERER_NAME));
        retValue.addAttributeRenderer(rendererFactory.getRenderer(TEMPLATE_RENDERER_NAME));
        retValue.addAttributeRenderer(rendererFactory.getRenderer(STRING_RENDERER_NAME));
        return retValue;
    }

    @Override
    protected AttributeEvaluatorFactory createAttributeEvaluatorFactory(
            ApplicationContext applicationContext,
            LocaleResolver resolver) {

        BasicAttributeEvaluatorFactory attributeEvaluatorFactory = new BasicAttributeEvaluatorFactory(new DirectAttributeEvaluator());
        attributeEvaluatorFactory.registerAttributeEvaluator("OGNL", createOGNLEvaluator());
        attributeEvaluatorFactory.registerAttributeEvaluator("EL", createELEvaluator(applicationContext));

        return attributeEvaluatorFactory;
    }

    @Override
    protected <T> PatternDefinitionResolver<T> createPatternDefinitionResolver(Class<T> customizationKeyClass) {
        DefinitionPatternMatcherFactory wildcardFactory = new WildcardDefinitionPatternMatcherFactory();
        DefinitionPatternMatcherFactory regexpFactory = new RegexpDefinitionPatternMatcherFactory();
        PrefixedPatternDefinitionResolver<T> resolver = new PrefixedPatternDefinitionResolver<>();

        resolver.registerDefinitionPatternMatcherFactory(PATTERN_WILDCARD, wildcardFactory);
        resolver.registerDefinitionPatternMatcherFactory(PATTERN_REGEXP, regexpFactory);

        return resolver;
    }

    @Override
    protected List<ApplicationResource> getSources(ApplicationContext applicationContext) {
        Collection<ApplicationResource> resources = applicationContext.getResources(getTilesDefinitionPattern(applicationContext.getInitParams()));

        List<ApplicationResource> filteredResources = new ArrayList<>();
        if (resources != null) {
            for (ApplicationResource resource : resources) {
                if (Locale.ROOT.equals(resource.getLocale())) {
                    filteredResources.add(resource);
                }
            }
        }

        return filteredResources;
    }

    protected String getTilesDefinitionPattern(Map<String, String> params) {
        if (params.containsKey(DefinitionsFactory.DEFINITIONS_CONFIG)) {
            return params.get(DefinitionsFactory.DEFINITIONS_CONFIG);
        }
        return TILES_DEFAULT_PATTERN;
    }

    protected ELAttributeEvaluator createELEvaluator(ApplicationContext applicationContext) {
        ELAttributeEvaluator evaluator = new ELAttributeEvaluator();
        JspExpressionFactoryFactory efFactory = new JspExpressionFactoryFactory();
        efFactory.setApplicationContext(applicationContext);
        evaluator.setExpressionFactory(efFactory.getExpressionFactory());
        ELResolver elResolver = new CompositeELResolver() {
            {
                BeanELResolver beanElResolver = new BeanELResolver(false);
                add(new ScopeELResolver());
                add(new TilesContextELResolver(beanElResolver));
                add(new TilesContextBeanELResolver());
                add(new ArrayELResolver(false));
                add(new ListELResolver(false));
                add(new MapELResolver(false));
                add(new ResourceBundleELResolver());
                add(beanElResolver);
            }
        };
        evaluator.setResolver(elResolver);
        return evaluator;
    }

    protected OGNLAttributeEvaluator createOGNLEvaluator() {
        try {
            PropertyAccessor objectPropertyAccessor = OgnlRuntime.getPropertyAccessor(Object.class);
            PropertyAccessor applicationContextPropertyAccessor = new NestedObjectDelegatePropertyAccessor<>(
                    new TilesApplicationContextNestedObjectExtractor(), objectPropertyAccessor);
            PropertyAccessor anyScopePropertyAccessor = new AnyScopePropertyAccessor();
            PropertyAccessor scopePropertyAccessor = new ScopePropertyAccessor();
            PropertyAccessorDelegateFactory<Request> factory = new TilesContextPropertyAccessorDelegateFactory(
                    objectPropertyAccessor, applicationContextPropertyAccessor, anyScopePropertyAccessor,
                    scopePropertyAccessor);
            PropertyAccessor tilesRequestAccessor = new DelegatePropertyAccessor<>(factory);
            OgnlRuntime.setPropertyAccessor(Request.class, tilesRequestAccessor);
            return new OGNLAttributeEvaluator();
        } catch (OgnlException e) {
            throw new TilesContainerFactoryException("Cannot initialize OGNL evaluator", e);
        }
    }

}