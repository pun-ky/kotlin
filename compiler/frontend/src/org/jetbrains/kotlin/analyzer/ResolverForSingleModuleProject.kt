/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analyzer

import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.builtins.DefaultBuiltIns
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.context.withModule
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.psi.KtFile
import kotlin.reflect.KProperty0

/**
 * A resolver which creates all infrastructure eagerly (don't use it where the project structure is complex and startup time matters!)
 *
 * Provides a simple builder-like DSL for creation.
 *
 * If the project structure is simple, and some particular config (like, built-ins) is the same for all modules, you can pass it
 * in constructor-call:
 *
 *   ```
 *   EagerResolverForProject("my resolver", projectContext, resolverForModuleFactory, constantBuiltIns = KotlinBuiltIns.DEFAULT)
 *   ```
 *
 * Otherwise, you can configure it in the trailing lambda:
 *
 *   ```
 *   EagerResolverForProject("my resolver", projectContext, resolverForModuleFactory) {
 *       builtIns { moduleInfo: ModuleInfo ->
 *           // complex, module-dependent logic of finding builtins
 *       }
 *   }
 *   ```
 *
 * Descriptors for module infos passed in the constructor will be created automatically. Additionally, you can use [addModuleInfo]
 * to add a module info with pre-created module descriptor
 */
class EagerResolverForProject<M : ModuleInfo>(
    override val name: String,
    private val projectContext: ProjectContext,
    private val resolverForModuleFactory: ResolverForModuleFactory,
    moduleInfosToCreateDescriptorsFor: Collection<M> = emptyList(),
    constantSynthethicFiles: Collection<KtFile>? = null,
    constantSearchScope: GlobalSearchScope? = null,
    constantSdkDependency: M? = null,
    constantBuiltIns: KotlinBuiltIns? = null,
    constantLanguageVersionSettings: LanguageVersionSettings? = null,
) : ResolverForProject<M>() {
    private val allModuleInfos: MutableSet<M> = moduleInfosToCreateDescriptorsFor.toMutableSet()
    private val moduleInfosToDescriptors: MutableMap<M, ModuleDescriptorImpl> = mutableMapOf()
    private lateinit var descriptorsToModuleInfos: Map<ModuleDescriptor, M>
    private val resolverByModuleDescriptor: MutableMap<ModuleDescriptor, ResolverForModule> = mutableMapOf()

    private lateinit var _syntheticFiles: (M) -> Collection<KtFile>
    private lateinit var _searchScope: (M) -> GlobalSearchScope
    private var _sdkDependency: (M) -> M? = { null }
    private var _builtIns: (M) -> KotlinBuiltIns = { DefaultBuiltIns.Instance }
    private lateinit var _languageVersionSettings: (M) -> LanguageVersionSettings

    private var isMutable: Boolean = true

    init {
        if (constantSynthethicFiles != null) _syntheticFiles = constantSynthethicFiles.asFunction()
        if (constantSearchScope != null) _searchScope = constantSearchScope.asFunction()
        if (constantSdkDependency != null) _sdkDependency = constantSdkDependency.asFunction()
        if (constantBuiltIns != null) _builtIns = constantBuiltIns.asFunction()
        if (constantLanguageVersionSettings != null) _languageVersionSettings = constantLanguageVersionSettings.asFunction()
    }


    // DSL
    fun syntheticFilesForModule(f: (M) -> Collection<KtFile>) {
        ensureMutable()
        _syntheticFiles = f
    }

    fun searchScopeForModule(f: (M) -> GlobalSearchScope) {
        ensureMutable()
        _searchScope = f
    }

    fun sdkDependencyForModule(f: (M) -> M) {
        ensureMutable()
        _sdkDependency = f
    }

    fun builtInsForModule(f: (M) -> KotlinBuiltIns) {
        ensureMutable()
        _builtIns = f
    }

    fun languageVersionSettingsForModule(f: (M) -> LanguageVersionSettings) {
        ensureMutable()
        _languageVersionSettings = f
    }

    fun addModuleInfo(moduleInfo: M, descriptor: ModuleDescriptorImpl? = null) {
        ensureMutable()
        allModuleInfos += moduleInfo
        if (descriptor != null) moduleInfosToDescriptors[moduleInfo] = descriptor
    }

    fun build(): EagerResolverForProject<M> {
        // 1. Check that all needed callbacks are passed
        if (!this::_syntheticFiles.isInitialized) error("Synthetic files are not set")
        if (!this::_searchScope.isInitialized) error("Search scope is not set")
        if (!this::_languageVersionSettings.isInitialized) error("Language version settings are not set")

        // 2. Build descriptors for module infos without descriptors
        val moduleInfosToCreateDescriptorsFor = allModuleInfos - moduleInfosToDescriptors.keys.toSet()
        for (moduleInfo in moduleInfosToCreateDescriptorsFor) {
            moduleInfosToDescriptors[moduleInfo] = createDescriptor(moduleInfo)
        }

        // 3. Build reverse map
        descriptorsToModuleInfos = moduleInfosToDescriptors.entries.associateBy({ it.value }) { it.key }

        // 4. Build resolvers for all modules
        for (moduleInfo in allModuleInfos) {
            val descriptor = moduleInfosToDescriptors[moduleInfo]!!
            resolverByModuleDescriptor[descriptor] = createResolver(moduleInfo, descriptor)
        }

        // 5. Freeze
        isMutable = false

        return this
    }

    override fun tryGetResolverForModule(moduleInfo: M): ResolverForModule? {
        return moduleInfosToDescriptors[moduleInfo]?.let { resolverForModuleDescriptor(it) }
    }

    override fun descriptorForModule(moduleInfo: M): ModuleDescriptorImpl =
        moduleInfosToDescriptors[moduleInfo] ?: diagnoseUnknownModuleInfo(moduleInfosToDescriptors.keys.toList())

    override fun moduleInfoForModuleDescriptor(moduleDescriptor: ModuleDescriptor): M =
        descriptorsToModuleInfos[moduleDescriptor] ?: error("Unknown module: $moduleDescriptor")

    override fun resolverForModuleDescriptor(descriptor: ModuleDescriptor): ResolverForModule =
        resolverByModuleDescriptor[descriptor]!!

    override fun diagnoseUnknownModuleInfo(infos: List<ModuleInfo>): Nothing = error("Unknown module info requested: $infos")

    override val allModules: Collection<M>
        get() = moduleInfosToDescriptors.keys.map { it }

    override fun isResolverForModuleDescriptorComputed(descriptor: ModuleDescriptor): Boolean = true

    private fun createDescriptor(module: M): ModuleDescriptorImpl {
        val moduleDescriptor = ModuleDescriptorImpl(
            module.name,
            projectContext.storageManager,
            _builtIns(module),
            module.platform,
            module.capabilities,
            module.stableName,
        )

        moduleDescriptor.setDependencies(
            LazyModuleDependencies(
                projectContext.storageManager,
                module,
                _sdkDependency(module),
                this
            )
        )

        val content = ModuleContent(module, _syntheticFiles(module), _searchScope(module))
        moduleDescriptor.initialize(
            DelegatingPackageFragmentProvider(
                this, moduleDescriptor, content,
                PackageOracleFactory.OptimisticFactory.createOracle(module)
            )
        )
        return moduleDescriptor
    }

    private fun createResolver(moduleInfo: M, descriptor: ModuleDescriptorImpl): ResolverForModule =
        resolverForModuleFactory.createResolverForModule(
            descriptor,
            projectContext.withModule(descriptor),
            ModuleContent(moduleInfo, _syntheticFiles(moduleInfo), _searchScope(moduleInfo)),
            this,
            _languageVersionSettings(moduleInfo)
        )

    private fun ensureMutable() {
        if (!isMutable) error("Can't mutate resolver after creation")
    }

    companion object {
        fun <M : ModuleInfo> create(
            debugName: String,
            projectContext: ProjectContext,
            resolverForModuleFactory: ResolverForModuleFactory,
            moduleInfosToCreateDescriptorsFor: Collection<M> = emptyList(),
            constantSynthethicFiles: Collection<KtFile>? = null,
            constantSearchScope: GlobalSearchScope? = null,
            constantSdkDependency: M? = null,
            constantBuiltIns: KotlinBuiltIns? = null,
            constantLanguageVersionSettings: LanguageVersionSettings? = null,
            configure: (EagerResolverForProject<M>).() -> Unit = { },
        ): EagerResolverForProject<M> {
            return EagerResolverForProject(
                debugName,
                projectContext,
                resolverForModuleFactory,
                moduleInfosToCreateDescriptorsFor,
                constantSynthethicFiles,
                constantSearchScope,
                constantSdkDependency,
                constantBuiltIns,
                constantLanguageVersionSettings,
            ).apply(configure).build()
        }
    }
}

private fun <M, T> T.asFunction(): (M) -> T = { this }