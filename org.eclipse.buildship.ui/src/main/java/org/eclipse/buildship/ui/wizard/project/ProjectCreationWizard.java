/*
 * Copyright (c) 2015 the original author or authors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Simon Scholz (vogella GmbH) - initial API and implementation and initial documentation
 */

package org.eclipse.buildship.ui.wizard.project;

import java.io.File;
import java.util.List;

import org.gradle.tooling.CancellationToken;
import org.gradle.tooling.ProgressListener;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.io.CharStreams;
import com.google.common.util.concurrent.FutureCallback;

import com.gradleware.tooling.toolingmodel.OmniBuildEnvironment;
import com.gradleware.tooling.toolingmodel.OmniGradleBuild;
import com.gradleware.tooling.toolingmodel.repository.TransientRequestAttributes;
import com.gradleware.tooling.toolingmodel.util.Pair;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.jface.dialogs.IPageChangeProvider;
import org.eclipse.jface.dialogs.IPageChangedListener;
import org.eclipse.jface.dialogs.PageChangedEvent;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.IWizardContainer;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.ui.INewWizard;
import org.eclipse.ui.IWorkbench;

import org.eclipse.buildship.core.CorePlugin;
import org.eclipse.buildship.core.configuration.BuildConfiguration;
import org.eclipse.buildship.core.configuration.RunConfiguration;
import org.eclipse.buildship.core.console.ProcessStreams;
import org.eclipse.buildship.core.projectimport.ProjectImportConfiguration;
import org.eclipse.buildship.core.projectimport.ProjectPreviewJob;
import org.eclipse.buildship.core.util.file.FileUtils;
import org.eclipse.buildship.core.util.progress.AsyncHandler;
import org.eclipse.buildship.core.util.progress.DelegatingProgressListener;
import org.eclipse.buildship.core.workspace.GradleBuild;
import org.eclipse.buildship.core.workspace.NewProjectHandler;
import org.eclipse.buildship.ui.HelpContext;
import org.eclipse.buildship.ui.UiPlugin;
import org.eclipse.buildship.ui.util.workbench.WorkingSetUtils;

/**
 * Eclipse wizard for creating Gradle projects in the workspace.
 */
public final class ProjectCreationWizard extends AbstractProjectWizard implements INewWizard {

    /**
     * The section name declaration for {@link org.eclipse.jface.dialogs.DialogSettings} where the import wizard stores its
     * preferences.
     *
     * @see org.eclipse.jface.dialogs.DialogSettings#getOrCreateSection(IDialogSettings, String)
     */
    private static final String PROJECT_CREATION_DIALOG_SETTINGS = "org.eclipse.buildship.ui.wizard.project.creation"; //$NON-NLS-1$

    /**
     * Preference key that flags whether the welcome page should be shown as part of the creation wizard.
     */
    private static final String PREF_SHOW_WELCOME_PAGE = "org.eclipse.buildship.ui.wizard.project.creation.showWelcomePage"; //$NON-NLS-1$

    /**
     * The Gradle tasks to run to initialize a new Gradle Java project, i.e.
     * <code>gradle init --type java-library</code>.
     */
    private static final ImmutableList<String> GRADLE_INIT_TASK_CMD_LINE = ImmutableList.of("init", "--type", "java-library");

    // the pages to display in the wizard
    private final GradleWelcomeWizardPage welcomeWizardPage;
    private final NewGradleProjectWizardPage newGradleProjectPage;
    private final GradleOptionsWizardPage gradleOptionsPage;
    private final ProjectPreviewWizardPage projectPreviewPage;

    // the controllers that contain the wizard logic
    private final ProjectImportWizardController importController;
    private final ProjectCreationWizardController creationController;
    private final IPageChangedListener pageChangeListener;

    /**
     * Creates a new instance and uses the {@link org.eclipse.jface.dialogs.DialogSettings} from {@link org.eclipse.buildship.ui.UiPlugin}..
     */
    @SuppressWarnings("UnusedDeclaration")
    public ProjectCreationWizard() {
        this(getOrCreateDialogSection(UiPlugin.getInstance().getDialogSettings()));
    }

    /**
     * Creates a new instance and uses the given {@link org.eclipse.jface.dialogs.DialogSettings}.
     *
     * @param dialogSettings          the dialog settings to store/retrieve dialog preferences
     */
    public ProjectCreationWizard(IDialogSettings dialogSettings) {
        super(PREF_SHOW_WELCOME_PAGE);

        // store the dialog settings on the wizard and use them to retrieve / persist the most
        // recent values entered by the user
        setDialogSettings(dialogSettings);

        // instantiate the controllers for this wizard
        this.importController = new ProjectImportWizardController(this);
        this.creationController = new ProjectCreationWizardController(this);
        this.pageChangeListener = new ProjectCreatingPageChangedListener(this);

        // instantiate the pages and pass the configuration objects that serve as
        // the data models of the wizard
        final ProjectImportConfiguration importConfiguration = this.importController.getConfiguration();
        ProjectCreationConfiguration creationConfiguration = this.creationController.getConfiguration();
        WelcomePageContent welcomePageContent = WelcomePageContentFactory.createCreationWizardWelcomePageContent();
        this.welcomeWizardPage = new GradleWelcomeWizardPage(importConfiguration, welcomePageContent);
        this.newGradleProjectPage = new NewGradleProjectWizardPage(importConfiguration, creationConfiguration);
        this.gradleOptionsPage = new GradleOptionsWizardPage(importConfiguration,
                ProjectWizardMessages.Title_NewGradleProjectOptionsWizardPage,
                ProjectWizardMessages.InfoMessage_NewGradleProjectOptionsWizardPageDefault,
                ProjectWizardMessages.InfoMessage_NewGradleProjectOptionsWizardPageContext);
        this.projectPreviewPage = new ProjectPreviewWizardPage(importConfiguration, new ProjectPreviewWizardPage.ProjectPreviewLoader() {
            @Override
            public Job loadPreview(FutureCallback<Pair<OmniBuildEnvironment, OmniGradleBuild>> resultHandler, List<ProgressListener> listeners) {
                ProjectPreviewJob projectPreviewJob = new ProjectPreviewJob(importConfiguration, listeners, new NewGradleProjectInitializer(importConfiguration, listeners), resultHandler);
                projectPreviewJob.schedule();
                return projectPreviewJob;
            }
        }, ProjectWizardMessages.Title_NewGradleProjectPreviewWizardPage, ProjectWizardMessages.InfoMessage_NewGradleProjectPreviewWizardPageDefault, ProjectWizardMessages.InfoMessage_NewGradleProjectPreviewWizardPageContext);
    }

    @Override
    public void setContainer(IWizardContainer wizardContainer) {
        if (wizardContainer instanceof IPageChangeProvider) {
            IPageChangeProvider pageChangeProvider = (IPageChangeProvider) wizardContainer;
            pageChangeProvider.addPageChangedListener(this.pageChangeListener);
        }
        super.setContainer(wizardContainer);
    }

    @Override
    public void init(IWorkbench workbench, IStructuredSelection selection) {
        List<String> workingSetNames = WorkingSetUtils.getSelectedWorkingSetNames(selection);
        if (!workingSetNames.isEmpty()) {
            this.importController.getConfiguration().setApplyWorkingSets(true);
            this.importController.getConfiguration().setWorkingSets(workingSetNames);
        }
    }

    @Override
    public String getWindowTitle() {
        return ProjectWizardMessages.Title_NewGradleProjectWizardPage;
    }

    @Override
    public void addPages() {
        // assign wizard pages to this wizard
        if (isShowWelcomePage()) {
            addPage(this.welcomeWizardPage);
        }
        addPage(this.newGradleProjectPage);
        addPage(this.gradleOptionsPage);
        addPage(this.projectPreviewPage);

        // show progress bar when getContainer().run() is called
        setNeedsProgressMonitor(true);

        // disable help on all wizard pages
        setHelpAvailable(false);
    }

    @Override
    public boolean performFinish() {
        return this.importController.performImportProject(new NewGradleProjectInitializer(this.importController.getConfiguration()), NewProjectHandler.IMPORT_AND_MERGE);
    }

    @Override
    public boolean performCancel() {
        // if the projectPreviewPage is active the project has already been created and
        // needs to be removed
        IWizardPage currentPage = getContainer().getCurrentPage();
        if (this.projectPreviewPage.equals(currentPage)) {
            File projectDir = ProjectCreationWizard.this.importController.getConfiguration().getProjectDir().getValue();
            if (projectDir != null) {
                FileUtils.deleteRecursively(projectDir);
            }
        }
        return true;
    }

    @Override
    public String getHelpContextId() {
        return HelpContext.PROJECT_CREATION;
    }

    @Override
    public void dispose() {
        if (getContainer() instanceof IPageChangeProvider) {
            IPageChangeProvider pageChangeProvider = (IPageChangeProvider) getContainer();
            pageChangeProvider.removePageChangedListener(this.pageChangeListener);
        }
        super.dispose();
    }

    private static IDialogSettings getOrCreateDialogSection(IDialogSettings dialogSettings) {
        // in Eclipse 3.6 the method DialogSettings#getOrCreateSection does not exist
        IDialogSettings section = dialogSettings.getSection(PROJECT_CREATION_DIALOG_SETTINGS);
        if (section == null) {
            section = dialogSettings.addNewSection(PROJECT_CREATION_DIALOG_SETTINGS);
        }
        return section;
    }

    /**
     * Listens to page changes and either creates or deletes the new project depending from which
     * page to which page the user is switching.
     */
    private static final class ProjectCreatingPageChangedListener implements IPageChangedListener {

        private final ProjectCreationWizard projectCreationWizard;
        private IWizardPage previousPage;

        private ProjectCreatingPageChangedListener(ProjectCreationWizard projectCreationWizard) {
            this.projectCreationWizard = projectCreationWizard;
            this.previousPage = projectCreationWizard.newGradleProjectPage;
        }

        @Override
        public void pageChanged(PageChangedEvent event) {
            if (this.projectCreationWizard.projectPreviewPage.equals(this.previousPage) && this.projectCreationWizard.gradleOptionsPage.equals(event.getSelectedPage())) {
                // user moved back, so we need to delete the previously created Gradle project
                File projectDir = this.projectCreationWizard.importController.getConfiguration().getProjectDir().getValue();
                if (projectDir != null) {
                    FileUtils.deleteRecursively(projectDir);

                    // at this point the new project wizard page has a validation error because the
                    // project location validator is triggered before the location directory is
                    // deleted. to clear the error and re-enable the wizard finish button, the page
                    // page completion is re-calculated and set here
                    this.projectCreationWizard.newGradleProjectPage.setPageComplete(this.projectCreationWizard.newGradleProjectPage.isPageComplete());
                }
            }
            this.previousPage = (IWizardPage) event.getSelectedPage();
        }

    }

    /**
     * Initializes a new Gradle project from the given configuration.
     */
    private static final class NewGradleProjectInitializer implements AsyncHandler {

        private final BuildConfiguration buildConfig;
        private final Optional<List<ProgressListener>> listeners;

        private NewGradleProjectInitializer(ProjectImportConfiguration configuration) {
            this.buildConfig = configuration.toBuildConfig();
            this.listeners = Optional.absent();
        }

        private NewGradleProjectInitializer(ProjectImportConfiguration configuration, List<ProgressListener> listeners) {
            this.buildConfig = configuration.toBuildConfig();
            this.listeners = Optional.of((List<ProgressListener>) ImmutableList.copyOf(listeners));
        }

        @Override
        public void run(IProgressMonitor monitor, CancellationToken token) {
            monitor.beginTask("Init Gradle project", IProgressMonitor.UNKNOWN);
            try {
                File projectDir = this.buildConfig.getRootProjectDirectory().getAbsoluteFile();
                if (!projectDir.exists()) {
                    if (projectDir.mkdir()) {
                        final List<String> tasks = GRADLE_INIT_TASK_CMD_LINE;
                        List<ProgressListener> progressListeners = this.listeners.isPresent() ? this.listeners.get() : ImmutableList.of(DelegatingProgressListener.withFullOutput(monitor));
                        GradleBuild gradleBuild = CorePlugin.gradleWorkspaceManager().getGradleBuild(this.buildConfig);
                        TransientRequestAttributes transientAttributes = getTransientRequestAttributes(progressListeners, token, monitor);
                        RunConfiguration runConfiguration = CorePlugin.configurationManager().createDefaultRunConfiguration(this.buildConfig);
                        gradleBuild.newBuildLauncher(runConfiguration, CharStreams.nullWriter(), transientAttributes).forTasks(tasks.toArray(new String[tasks.size()])).run();
                    }
                }
            } finally {
                monitor.done();
            }
        }
    }

    private static TransientRequestAttributes getTransientRequestAttributes(List<ProgressListener> progressListeners, CancellationToken token, IProgressMonitor monitor) {
        ProcessStreams streams = CorePlugin.processStreamsProvider().getBackgroundJobProcessStreams();
        ImmutableList<org.gradle.tooling.events.ProgressListener> noEventListeners = ImmutableList.<org.gradle.tooling.events.ProgressListener> of();
        return new TransientRequestAttributes(false, streams.getOutput(), streams.getError(), streams.getInput(), progressListeners, noEventListeners, token);
    }

}
