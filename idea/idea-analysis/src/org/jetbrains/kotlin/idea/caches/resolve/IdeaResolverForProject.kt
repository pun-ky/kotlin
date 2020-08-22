/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.caches.resolve

import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.util.ModificationTracker
import org.jetbrains.kotlin.analyzer.*
import org.jetbrains.kotlin.analyzer.common.CommonAnalysisParameters
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.jvm.JvmBuiltIns
import org.jetbrains.kotlin.caches.resolve.CompositeAnalyzerServices
import org.jetbrains.kotlin.caches.resolve.CompositeResolverForModuleFactory
import org.jetbrains.kotlin.caches.resolve.KotlinCacheService
import org.jetbrains.kotlin.caches.resolve.resolution
import org.jetbrains.kotlin.context.ProjectContext
import org.jetbrains.kotlin.context.withModule
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.idea.caches.project.*
import org.jetbrains.kotlin.idea.caches.project.IdeaModuleInfo
import org.jetbrains.kotlin.idea.caches.project.getNullableModuleInfo
import org.jetbrains.kotlin.idea.compiler.IDELanguageSettingsProvider
import org.jetbrains.kotlin.idea.project.IdeaEnvironment
import org.jetbrains.kotlin.idea.project.findAnalyzerServices
import org.jetbrains.kotlin.idea.project.useCompositeAnalysis
import org.jetbrains.kotlin.load.java.structure.JavaClass
import org.jetbrains.kotlin.load.java.structure.impl.JavaClassImpl
import org.jetbrains.kotlin.platform.idePlatformKind
import org.jetbrains.kotlin.platform.isCommon
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.RESOLUTION_ANCHOR_PROVIDER_CAPABILITY
import org.jetbrains.kotlin.resolve.ResolutionAnchorProvider
import org.jetbrains.kotlin.resolve.jvm.JvmPlatformParameters
import org.jetbrains.kotlin.utils.checkWithAttachment

class IdeaResolverForProject(
    private val debugName: String,
    private val projectContext: ProjectContext,
    modules: Collection<IdeaModuleInfo>,
    private val syntheticFilesByModule: Map<IdeaModuleInfo, Collection<KtFile>>,
    private val delegateResolver: ResolverForProject<IdeaModuleInfo> = EmptyResolverForProject(),
    private val fallbackModificationTracker: ModificationTracker? = null,
    private val packageOracleFactory: PackageOracleFactory = ServiceManager.getService(projectContext.project, IdePackageOracleFactory::class.java),
    private val resolutionAnchorProvider: ResolutionAnchorProvider = ServiceManager.getService(projectContext.project, ResolutionAnchorProvider::class.java),
    private val isReleaseCoroutines: Boolean? = null,
    // TODO(dsavvinov): this is needed only for non-composite analysis, extract separate resolver implementation instead
    private val constantSdkDependencyIfAny: SdkInfo? = null
) : ResolverForProject<IdeaModuleInfo>() {
    private class ModuleData(
        val moduleDescriptor: ModuleDescriptorImpl,
        val modificationTracker: ModificationTracker?
    ) {
        val modificationCount: Long = modificationTracker?.modificationCount ?: Long.MIN_VALUE

        fun isOutOfDate(): Boolean {
            val currentModCount = modificationTracker?.modificationCount
            return currentModCount != null && currentModCount > modificationCount
        }
    }

    private val builtInsCache: BuiltInsCache =
        (delegateResolver as? IdeaResolverForProject)?.builtInsCache ?: BuiltInsCache(projectContext, this)

    // Maps protected by ("projectContext.storageManager.lock")
    private val descriptorByModule = mutableMapOf<IdeaModuleInfo, ModuleData>()
    private val moduleInfoByDescriptor = mutableMapOf<ModuleDescriptorImpl, IdeaModuleInfo>()
    private val resolverByModuleDescriptor = mutableMapOf<ModuleDescriptor, ResolverForModule>()

    @Suppress("UNCHECKED_CAST")
    private val moduleInfoToResolvableInfo: Map<IdeaModuleInfo, IdeaModuleInfo> =
        modules.flatMap { module -> module.flatten().map { modulePart -> (modulePart as IdeaModuleInfo) to module } }
            .toMap()
            .also {
                assert(it.values.toSet() == modules.toSet())
            }

    override val name: String
        get() = "Resolver for '$debugName'"

    override val allModules: Collection<IdeaModuleInfo> by lazy {
        this.moduleInfoToResolvableInfo.keys + delegateResolver.allModules
    }

    override fun tryGetResolverForModule(moduleInfo: IdeaModuleInfo): ResolverForModule? {
        if (!isCorrectModuleInfo(moduleInfo)) {
            return null
        }
        return resolverForModuleDescriptor(doGetDescriptorForModule(moduleInfo))
    }

    override fun resolverForModuleDescriptor(descriptor: ModuleDescriptor): ResolverForModule {
        val moduleResolver = resolverForModuleDescriptorImpl(descriptor)

        // Please, attach exceptions from here to EA-214260 (see `resolverForModuleDescriptorImpl` comment)
        checkWithAttachment(
            moduleResolver != null,
            lazyMessage = { "$descriptor is not contained in resolver $name" },
            attachments = {
                it.withAttachment(
                    "resolverContents.txt",
                    "Expected module descriptor: $descriptor\n\n${renderResolversChainContents()}"
                )
            }
        )

        return moduleResolver
    }

    /**
     * We have a problem investigating EA-214260 (KT-40301), that is why we separated searching the
     * [ResolverForModule] and reporting the problem in [resolverForModuleDescriptor] (so we can tweak the reported information more
     * accurately).
     *
     * We use the fact that [ResolverForProject] have only two inheritors: [EmptyResolverForProject] and [AbstractResolverForProject].
     * So if the [delegateResolver] is not an [EmptyResolverForProject], it has to be [AbstractResolverForProject].
     *
     * Knowing that, we can safely use [resolverForModuleDescriptorImpl] recursively, and get the same result
     * as with [resolverForModuleDescriptor].
     */
    private fun resolverForModuleDescriptorImpl(descriptor: ModuleDescriptor): ResolverForModule? {
        return projectContext.storageManager.compute {
            val module = moduleInfoByDescriptor[descriptor]
            if (module == null) {
                if (delegateResolver is EmptyResolverForProject<*>) {
                    return@compute null
                }
                return@compute (delegateResolver as IdeaResolverForProject).resolverForModuleDescriptorImpl(descriptor)
            }
            resolverByModuleDescriptor.getOrPut(descriptor) {
                checkModuleIsCorrect(module)

                ResolverForModuleComputationTracker.getInstance(projectContext.project)?.onResolverComputed(module)

                val moduleContent = ModuleContent(module, syntheticFilesByModule[module] ?: listOf(), module.contentScope())
                val languageVersionSettings =
                    IDELanguageSettingsProvider.getLanguageVersionSettings(module, projectContext.project, isReleaseCoroutines)
                val resolverForModuleFactory = getResolverForModuleFactory(module)

                resolverForModuleFactory.createResolverForModule(
                    descriptor as ModuleDescriptorImpl,
                    projectContext.withModule(descriptor),
                    moduleContent,
                    this,
                    languageVersionSettings
                )
            }
        }
    }

    override fun descriptorForModule(moduleInfo: IdeaModuleInfo): ModuleDescriptorImpl {
        checkModuleIsCorrect(moduleInfo)
        return doGetDescriptorForModule(moduleInfo)
    }

    override fun moduleInfoForModuleDescriptor(moduleDescriptor: ModuleDescriptor): IdeaModuleInfo {
        return moduleInfoByDescriptor[moduleDescriptor] ?: delegateResolver.moduleInfoForModuleDescriptor(moduleDescriptor)
    }

    override fun diagnoseUnknownModuleInfo(infos: List<ModuleInfo>): Nothing {
        DiagnoseUnknownModuleInfoReporter.report(name, infos, allModules)
    }

    internal fun sdkDependency(module: IdeaModuleInfo): SdkInfo? {
        if (projectContext.project.useCompositeAnalysis) {
            require(constantSdkDependencyIfAny == null) { "Shouldn't pass SDK dependency manually for composite analysis mode" }
        }
        return constantSdkDependencyIfAny ?: module.findSdkAcrossDependencies()
    }

    private fun doGetDescriptorForModule(module: IdeaModuleInfo): ModuleDescriptorImpl {
        val moduleFromThisResolver = moduleInfoToResolvableInfo[module]
            ?: return delegateResolver.descriptorForModule(module)

        return projectContext.storageManager.compute {
            var moduleData = descriptorByModule.getOrPut(moduleFromThisResolver) {
                createModuleDescriptor(moduleFromThisResolver)
            }
            if (moduleData.isOutOfDate()) {
                moduleData = recreateModuleDescriptor(moduleFromThisResolver)
            }
            moduleData.moduleDescriptor
        }
    }

    private fun recreateModuleDescriptor(module: IdeaModuleInfo): ModuleData {
        val oldDescriptor = descriptorByModule[module]?.moduleDescriptor
        if (oldDescriptor != null) {
            oldDescriptor.isValid = false
            moduleInfoByDescriptor.remove(oldDescriptor)
            resolverByModuleDescriptor.remove(oldDescriptor)
            projectContext.project.messageBus.syncPublisher(ModuleDescriptorListener.TOPIC).moduleDescriptorInvalidated(oldDescriptor)
        }

        val moduleData = createModuleDescriptor(module)
        descriptorByModule[module] = moduleData

        return moduleData
    }

    private fun createModuleDescriptor(module: IdeaModuleInfo): ModuleData {
        val moduleDescriptor = ModuleDescriptorImpl(
            module.name,
            projectContext.storageManager,
            builtInsCache.getOrCreateIfNeeded(module),
            module.platform,
            module.capabilities + listOf(RESOLUTION_ANCHOR_PROVIDER_CAPABILITY to resolutionAnchorProvider),
            module.stableName,
        )
        moduleInfoByDescriptor[moduleDescriptor] = module
        setupModuleDescriptor(module, moduleDescriptor)
        val modificationTracker = (module as? TrackableModuleInfo)?.createModificationTracker() ?: fallbackModificationTracker
        return ModuleData(moduleDescriptor, modificationTracker)
    }

    private fun setupModuleDescriptor(module: IdeaModuleInfo, moduleDescriptor: ModuleDescriptorImpl) {
        moduleDescriptor.setDependencies(
            LazyModuleDependencies(
                projectContext.storageManager,
                module,
                sdkDependency(module),
                this
            )
        )

        val content = ModuleContent(module, syntheticFilesByModule[module] ?: emptyList(), module.contentScope())
        moduleDescriptor.initialize(
            DelegatingPackageFragmentProvider(
                this, moduleDescriptor, content,
                packageOracleFactory.createOracle(module)
            )
        )
    }

    private fun checkModuleIsCorrect(moduleInfo: IdeaModuleInfo) {
        if (!isCorrectModuleInfo(moduleInfo)) {
            diagnoseUnknownModuleInfo(listOf(moduleInfo))
        }
    }

    private fun renderResolversChainContents(): String {
        val resolversChain = generateSequence(this) { it.delegateResolver as? IdeaResolverForProject }

        return resolversChain.joinToString("\n\n") { resolver ->
            "Resolver: ${resolver.name}\n'moduleInfoByDescriptor' content:\n[${resolver.renderResolverModuleInfos()}]"
        }
    }

    private fun renderResolverModuleInfos(): String = projectContext.storageManager.compute {
        moduleInfoByDescriptor.entries.joinToString(",\n") { (descriptor, moduleInfo) ->
            """
            {
                moduleDescriptor: $descriptor
                moduleInfo: $moduleInfo
            }
            """.trimIndent()
        }
    }

    private fun getResolverForModuleFactory(moduleInfo: IdeaModuleInfo): ResolverForModuleFactory {
        val platform = moduleInfo.platform

        val jvmPlatformParameters = JvmPlatformParameters(
            packagePartProviderFactory = { IDEPackagePartProvider(it.moduleContentScope) },
            moduleByJavaClass = { javaClass: JavaClass ->
                val psiClass = (javaClass as JavaClassImpl).psi
                psiClass.getPlatformModuleInfo(JvmPlatforms.unspecifiedJvmPlatform)?.platformModule ?: psiClass.getNullableModuleInfo()
            },
            resolverForReferencedModule = { targetModuleInfo, referencingModuleInfo ->
                require(targetModuleInfo is IdeaModuleInfo && referencingModuleInfo is IdeaModuleInfo) {
                    "Unexpected modules passed through JvmPlatformParameters to IDE resolver ($targetModuleInfo, $referencingModuleInfo)"
                }
                tryGetResolverForModuleWithResolutionAnchorFallback(targetModuleInfo, referencingModuleInfo)
            }
        )

        val commonPlatformParameters = CommonAnalysisParameters(
            metadataPartProviderFactory = { IDEPackagePartProvider(it.moduleContentScope) }
        )

        return if (!projectContext.project.useCompositeAnalysis) {
            val parameters = when {
                platform.isJvm() -> jvmPlatformParameters
                platform.isCommon() -> commonPlatformParameters
                else -> PlatformAnalysisParameters.Empty
            }

            platform.idePlatformKind.resolution.createResolverForModuleFactory(parameters, IdeaEnvironment, platform)
        } else {
            CompositeResolverForModuleFactory(
                commonPlatformParameters,
                jvmPlatformParameters,
                platform,
                CompositeAnalyzerServices(platform.componentPlatforms.map { it.findAnalyzerServices() })
            )
        }
    }

    private fun tryGetResolverForModuleWithResolutionAnchorFallback(
        targetModuleInfo: IdeaModuleInfo,
        referencingModuleInfo: IdeaModuleInfo,
    ): ResolverForModule? {
        tryGetResolverForModule(targetModuleInfo)?.let { return it }

        return getResolverForProjectUsingResolutionAnchor(targetModuleInfo, referencingModuleInfo)
    }

    private fun getResolverForProjectUsingResolutionAnchor(
        targetModuleInfo: IdeaModuleInfo,
        referencingModuleInfo: IdeaModuleInfo
    ): ResolverForModule? {
        val moduleDescriptorOfReferencingModule = descriptorByModule[referencingModuleInfo]?.moduleDescriptor
            ?: error("$referencingModuleInfo is not contained in this resolver, which means incorrect use of anchor-aware search")

        val anchorModuleInfo = resolutionAnchorProvider.getResolutionAnchor(moduleDescriptorOfReferencingModule)?.moduleInfo ?: return null

        val resolverForProjectFromAnchorModule = KotlinCacheService.getInstance(projectContext.project)
            .getResolutionFacadeByModuleInfo(anchorModuleInfo, anchorModuleInfo.platform)
            ?.getResolverForProject()
            ?: return null

        require(resolverForProjectFromAnchorModule is IdeaResolverForProject) {
            "Resolution via anchor modules is expected to be used only from IDE resolvers"
        }

        return resolverForProjectFromAnchorModule.tryGetResolverForModule(targetModuleInfo)
    }

    private fun isCorrectModuleInfo(moduleInfo: IdeaModuleInfo) = moduleInfo in allModules

    override fun isResolverForModuleDescriptorComputed(descriptor: ModuleDescriptor): Boolean = projectContext.storageManager.compute {
        descriptor in resolverByModuleDescriptor
    }
}

interface BuiltInsCacheKey {
    object DefaultBuiltInsKey : BuiltInsCacheKey
}

// Important: ProjectContext must be from SDK to be sure that we won't run into deadlocks
class BuiltInsCache(private val projectContextFromSdkResolver: ProjectContext, private val resolverForSdk: IdeaResolverForProject) {
    private val cache = mutableMapOf<BuiltInsCacheKey, KotlinBuiltIns>()

    fun getOrCreateIfNeeded(module: IdeaModuleInfo): KotlinBuiltIns = projectContextFromSdkResolver.storageManager.compute {
        val sdk = resolverForSdk.sdkDependency(module)

        val key = module.platform.idePlatformKind.resolution.getKeyForBuiltIns(module, sdk)
        val cachedBuiltIns = cache[key]
        if (cachedBuiltIns != null) return@compute cachedBuiltIns

        // Note #1: we can't use .getOrPut, because we have to put builtIns into map *before* initialization
        // Note #2: it's OK to put not-initialized built-ins into public map, because access to [cache] is guarded by storageManager.lock
        val newBuiltIns = module.platform.idePlatformKind.resolution.createBuiltIns(module, projectContextFromSdkResolver, sdk)
        cache[key] = newBuiltIns

        if (newBuiltIns is JvmBuiltIns) {
            // SDK should be present, otherwise we wouldn't have created JvmBuiltIns in createBuiltIns
            val sdkDescriptor = resolverForSdk.descriptorForModule(sdk!!)

            val isAdditionalBuiltInsFeaturesSupported = module.supportsAdditionalBuiltInsMembers(projectContextFromSdkResolver.project)

            newBuiltIns.initialize(sdkDescriptor, isAdditionalBuiltInsFeaturesSupported)
        }

        return@compute newBuiltIns
    }
}