/*
 * Copyright (c) 2016 the original author or authors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 */
package org.eclipse.buildship.core.workspace.internal;

import java.io.File;
import java.util.Collection;
import java.util.List;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import com.gradleware.tooling.toolingmodel.OmniEclipseLinkedResource;
import com.gradleware.tooling.toolingmodel.OmniEclipseProject;
import com.gradleware.tooling.toolingmodel.OmniGradleProject;
import com.gradleware.tooling.toolingmodel.util.Maybe;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.SubMonitor;

import org.eclipse.buildship.core.CorePlugin;
import org.eclipse.buildship.core.GradlePluginsRuntimeException;
import org.eclipse.buildship.core.util.file.RelativePathUtils;

/**
 * Updates the derived resource markers of a project. Stores the last state in the preferences, so
 * we can remove the derived markers later.
 *
 * @author Stefan Oehme
 */
final class DerivedResourcesUpdater {

    private static final String PERSISTENT_PROP_NAME = "derivedResources";

    private final IProject project;
    private final IProject workspaceProject;
    private final OmniEclipseProject modelProject;

    private DerivedResourcesUpdater(IProject project, OmniEclipseProject modelProject) {
        this.project = Preconditions.checkNotNull(project);
        this.workspaceProject = Preconditions.checkNotNull(project);
        this.modelProject = Preconditions.checkNotNull(modelProject);
    }

    private void update(IProgressMonitor monitor) {
        SubMonitor progress = SubMonitor.convert(monitor, 2);
        try {
            List<String> derivedResources = getDerivedResources();
            removePreviousMarkers(derivedResources, progress.newChild(1));
            addNewMarkers(derivedResources, progress.newChild(1));
        } catch (CoreException e) {
            String message = String.format("Could not update derived resources on project %s.", this.project.getName());
            throw new GradlePluginsRuntimeException(message, e);
        } finally {
            if (monitor != null) {
                monitor.done();
            }
        }
    }

    private List<String> getDerivedResources() {
        List<String> derivedResources = Lists.newArrayList();
        derivedResources.add(".gradle");

        Optional<IFolder> possibleBuildDirectory = getBuildDirectory();
        if (possibleBuildDirectory.isPresent()) {
            IFolder buildDirectory = possibleBuildDirectory.get();
            derivedResources.add(buildDirectory.getName());
            if (buildDirectory.exists()) {
                // TODO (donat) move this to a separate updater class
                CorePlugin.workspaceOperations().markAsBuildFolder(buildDirectory);
            }
        }

        return derivedResources;
    }

    private void removePreviousMarkers(List<String> derivedResources, SubMonitor progress) throws CoreException {
        Collection<String> previouslyKnownDerivedResources = PersistentUpdaterUtils.getKnownItems(this.project, PERSISTENT_PROP_NAME);
        progress.setWorkRemaining(previouslyKnownDerivedResources.size());
        for (String resourceName : previouslyKnownDerivedResources) {
            setDerived(resourceName, false, progress.newChild(1));
        }
    }

    private void addNewMarkers(List<String> derivedResources, SubMonitor progress) throws CoreException {
        progress.setWorkRemaining(derivedResources.size());
        for (String resourceName : derivedResources) {
            setDerived(resourceName, true, progress.newChild(1));
        }
        PersistentUpdaterUtils.setKnownItems(this.project, PERSISTENT_PROP_NAME, derivedResources);
    }

    /*
     * If no build directory is available via the TAPI, use 'build'.
     * If build directory is physically contained in the project, use that folder.
     * If build directory is a linked resource, use the linked folder.
     * Optional.absent() if all of the above fail.
     */
    private Optional<IFolder> getBuildDirectory() {
        OmniGradleProject gradleProject = this.modelProject.getGradleProject();
        Maybe<File> buildDirectory = gradleProject.getBuildDirectory();
        if (buildDirectory.isPresent() && buildDirectory.get() != null) {
            Path buildDirLocation = new Path(buildDirectory.get().getPath());
            return normalizeBuildDirectory(buildDirLocation);
        } else {
            return Optional.of(this.workspaceProject.getFolder("build"));
        }
    }

    private Optional<IFolder> normalizeBuildDirectory(Path buildDirLocation) {
        IPath projectLocation = this.workspaceProject.getLocation();
        if (projectLocation.isPrefixOf(buildDirLocation)) {
            IPath relativePath = RelativePathUtils.getRelativePath(projectLocation, buildDirLocation);
            return Optional.of(this.workspaceProject.getFolder(relativePath));
        } else {
            for (OmniEclipseLinkedResource linkedResource : this.modelProject.getLinkedResources()) {
                if (buildDirLocation.toString().equals(linkedResource.getLocation())) {
                    return Optional.of(this.workspaceProject.getFolder(linkedResource.getName()));
                }
            }
            return Optional.absent();
        }
    }

    private void setDerived(String resourceName, boolean derived, SubMonitor progress) throws CoreException {
        IResource derivedResource = this.project.findMember(resourceName);
        if (derivedResource != null) {
            derivedResource.setDerived(derived, progress);
        }
    }

    static void update(IProject workspaceProject, OmniEclipseProject project, IProgressMonitor monitor) {
        new DerivedResourcesUpdater(workspaceProject, project).update(monitor);
    }
}
