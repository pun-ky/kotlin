/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analyzer

import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentProvider
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

class DelegatingPackageFragmentProvider<M : ModuleInfo>(
    private val resolverForProject: ResolverForProject<M>,
    private val module: ModuleDescriptor,
    moduleContent: ModuleContent<M>,
    private val packageOracle: PackageOracle
) : PackageFragmentProvider {
    private val syntheticFilePackages = moduleContent.syntheticFiles.map { it.packageFqName }.toSet()

    override fun getPackageFragments(fqName: FqName): List<PackageFragmentDescriptor> {
        if (certainlyDoesNotExist(fqName)) return emptyList()

        return resolverForProject.resolverForModuleDescriptor(module).packageFragmentProvider.getPackageFragments(fqName)
    }

    override fun getSubPackagesOf(fqName: FqName, nameFilter: (Name) -> Boolean): Collection<FqName> {
        if (certainlyDoesNotExist(fqName)) return emptyList()

        return resolverForProject.resolverForModuleDescriptor(module).packageFragmentProvider.getSubPackagesOf(fqName, nameFilter)
    }

    private fun certainlyDoesNotExist(fqName: FqName): Boolean {
        if (resolverForProject.isResolverForModuleDescriptorComputed(module))
            return false // let this request get cached inside delegate

        return !packageOracle.packageExists(fqName) && fqName !in syntheticFilePackages
    }
}

object DiagnoseUnknownModuleInfoReporter {
    fun report(name: String, infos: List<ModuleInfo>, allModules: Collection<ModuleInfo>): Nothing {
        val message = "$name does not know how to resolve $infos, allModules: $allModules"
        when {
            name.contains(ResolverForProject.resolverForSdkName) -> errorInSdkResolver(message)
            name.contains(ResolverForProject.resolverForLibrariesName) -> errorInLibrariesResolver(message)
            name.contains(ResolverForProject.resolverForModulesName) -> {
                when {
                    infos.isEmpty() -> errorInModulesResolverWithEmptyInfos(message)
                    infos.size == 1 -> {
                        val infoAsString = infos.single().toString()
                        when {
                            infoAsString.contains("ScriptDependencies") -> errorInModulesResolverWithScriptDependencies(message)
                            infoAsString.contains("Library") -> errorInModulesResolverWithLibraryInfo(message)
                            else -> errorInModulesResolver(message)
                        }
                    }
                    else -> throw errorInModulesResolver(message)
                }
            }
            name.contains(ResolverForProject.resolverForScriptDependenciesName) -> errorInScriptDependenciesInfoResolver(message)
            name.contains(ResolverForProject.resolverForSpecialInfoName) -> {
                when {
                    name.contains("ScriptModuleInfo") -> errorInScriptModuleInfoResolver(message)
                    else -> errorInSpecialModuleInfoResolver(message)
                }
            }
            else -> otherError(message)
        }
    }

    // Do not inline 'error*'-methods, they are needed to avoid Exception Analyzer merging those AssertionErrors

    private fun errorInSdkResolver(message: String): Nothing = throw AssertionError(message)
    private fun errorInLibrariesResolver(message: String): Nothing = throw AssertionError(message)
    private fun errorInModulesResolver(message: String): Nothing = throw AssertionError(message)

    private fun errorInModulesResolverWithEmptyInfos(message: String): Nothing = throw AssertionError(message)
    private fun errorInModulesResolverWithScriptDependencies(message: String): Nothing = throw AssertionError(message)
    private fun errorInModulesResolverWithLibraryInfo(message: String): Nothing = throw AssertionError(message)

    private fun errorInScriptDependenciesInfoResolver(message: String): Nothing = throw AssertionError(message)
    private fun errorInScriptModuleInfoResolver(message: String): Nothing = throw AssertionError(message)
    private fun errorInSpecialModuleInfoResolver(message: String): Nothing = throw AssertionError(message)

    private fun otherError(message: String): Nothing = throw AssertionError(message)
}
