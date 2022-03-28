/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.onboarding

import android.content.Context
import android.net.Uri
import com.airbnb.mvrx.MavericksViewModelFactory
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.di.MavericksAssistedViewModelFactory
import im.vector.app.core.di.hiltMavericksViewModelFactory
import im.vector.app.core.extensions.configureAndStart
import im.vector.app.core.extensions.vectorStore
import im.vector.app.core.platform.VectorViewModel
import im.vector.app.core.resources.StringProvider
import im.vector.app.core.utils.ensureTrailingSlash
import im.vector.app.features.VectorFeatures
import im.vector.app.features.VectorOverrides
import im.vector.app.features.analytics.AnalyticsTracker
import im.vector.app.features.analytics.extensions.toTrackingValue
import im.vector.app.features.analytics.plan.UserProperties
import im.vector.app.features.login.HomeServerConnectionConfigFactory
import im.vector.app.features.login.LoginConfig
import im.vector.app.features.login.LoginMode
import im.vector.app.features.login.ReAuthHelper
import im.vector.app.features.login.ServerType
import im.vector.app.features.login.SignMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.MatrixPatterns.getDomain
import org.matrix.android.sdk.api.auth.AuthenticationService
import org.matrix.android.sdk.api.auth.HomeServerHistoryService
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.auth.data.LoginFlowTypes
import org.matrix.android.sdk.api.auth.login.LoginWizard
import org.matrix.android.sdk.api.auth.registration.FlowResult
import org.matrix.android.sdk.api.auth.registration.RegistrationResult
import org.matrix.android.sdk.api.auth.registration.RegistrationWizard
import org.matrix.android.sdk.api.auth.registration.Stage
import org.matrix.android.sdk.api.auth.wellknown.WellknownResult
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixIdFailure
import org.matrix.android.sdk.api.session.Session
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.CancellationException

/**
 *
 */
class OnboardingViewModel @AssistedInject constructor(
        @Assisted initialState: OnboardingViewState,
        private val applicationContext: Context,
        private val authenticationService: AuthenticationService,
        private val activeSessionHolder: ActiveSessionHolder,
        private val homeServerConnectionConfigFactory: HomeServerConnectionConfigFactory,
        private val reAuthHelper: ReAuthHelper,
        private val stringProvider: StringProvider,
        private val homeServerHistoryService: HomeServerHistoryService,
        private val vectorFeatures: VectorFeatures,
        private val analyticsTracker: AnalyticsTracker,
        private val uriFilenameResolver: UriFilenameResolver,
        private val registrationActionHandler: RegistrationActionHandler,
        private val vectorOverrides: VectorOverrides
) : VectorViewModel<OnboardingViewState, OnboardingAction, OnboardingViewEvents>(initialState) {

    @AssistedFactory
    interface Factory : MavericksAssistedViewModelFactory<OnboardingViewModel, OnboardingViewState> {
        override fun create(initialState: OnboardingViewState): OnboardingViewModel
    }

    companion object : MavericksViewModelFactory<OnboardingViewModel, OnboardingViewState> by hiltMavericksViewModelFactory()

    init {
        getKnownCustomHomeServersUrls()
        observeDataStore()
    }

    private fun getKnownCustomHomeServersUrls() {
        setState {
            copy(knownCustomHomeServersUrls = homeServerHistoryService.getKnownServersUrls())
        }
    }

    private fun observeDataStore() = viewModelScope.launch {
        vectorOverrides.forceLoginFallback.setOnEach { isForceLoginFallbackEnabled ->
            copy(isForceLoginFallbackEnabled = isForceLoginFallbackEnabled)
        }
    }

    // Store the last action, to redo it after user has trusted the untrusted certificate
    private var lastAction: OnboardingAction? = null
    private var currentHomeServerConnectionConfig: HomeServerConnectionConfig? = null

    private val matrixOrgUrl = stringProvider.getString(R.string.matrix_org_server_url).ensureTrailingSlash()

    private val registrationWizard: RegistrationWizard
        get() = authenticationService.getRegistrationWizard()

    val currentThreePid: String?
        get() = registrationWizard.currentThreePid

    // True when login and password has been sent with success to the homeserver
    val isRegistrationStarted: Boolean
        get() = authenticationService.isRegistrationStarted

    private val loginWizard: LoginWizard?
        get() = authenticationService.getLoginWizard()

    private var loginConfig: LoginConfig? = null

    private var currentJob: Job? = null
        set(value) {
            // Cancel any previous Job
            field?.cancel()
            field = value
        }

    override fun handle(action: OnboardingAction) {
        when (action) {
            is OnboardingAction.OnGetStarted               -> handleSplashAction(action.resetLoginConfig, action.onboardingFlow)
            is OnboardingAction.OnIAlreadyHaveAnAccount    -> handleSplashAction(action.resetLoginConfig, action.onboardingFlow)
            is OnboardingAction.UpdateUseCase              -> handleUpdateUseCase(action)
            OnboardingAction.ResetUseCase                  -> resetUseCase()
            is OnboardingAction.UpdateServerType           -> handleUpdateServerType(action)
            is OnboardingAction.UpdateSignMode             -> handleUpdateSignMode(action)
            is OnboardingAction.InitWith                   -> handleInitWith(action)
            is OnboardingAction.UpdateHomeServer           -> handleUpdateHomeserver(action).also { lastAction = action }
            is OnboardingAction.LoginOrRegister            -> handleLoginOrRegister(action).also { lastAction = action }
            is OnboardingAction.Register                   -> handleRegisterWith(action).also { lastAction = action }
            is OnboardingAction.LoginWithToken             -> handleLoginWithToken(action)
            is OnboardingAction.WebLoginSuccess            -> handleWebLoginSuccess(action)
            is OnboardingAction.ResetPassword              -> handleResetPassword(action)
            is OnboardingAction.ResetPasswordMailConfirmed -> handleResetPasswordMailConfirmed()
            is OnboardingAction.PostRegisterAction         -> handleRegisterAction(action.registerAction)
            is OnboardingAction.ResetAction                -> handleResetAction(action)
            is OnboardingAction.UserAcceptCertificate      -> handleUserAcceptCertificate(action)
            OnboardingAction.ClearHomeServerHistory        -> handleClearHomeServerHistory()
            is OnboardingAction.UpdateDisplayName          -> updateDisplayName(action.displayName)
            OnboardingAction.UpdateDisplayNameSkipped      -> handleDisplayNameStepComplete()
            OnboardingAction.UpdateProfilePictureSkipped   -> completePersonalization()
            OnboardingAction.PersonalizeProfile            -> handlePersonalizeProfile()
            is OnboardingAction.ProfilePictureSelected     -> handleProfilePictureSelected(action)
            OnboardingAction.SaveSelectedProfilePicture    -> updateProfilePicture()
            is OnboardingAction.PostViewEvent              -> _viewEvents.post(action.viewEvent)
            OnboardingAction.StopEmailValidationCheck      -> cancelWaitForEmailValidation()
        }
    }

    private fun handleSplashAction(resetConfig: Boolean, onboardingFlow: OnboardingFlow) {
        if (resetConfig) {
            loginConfig = null
        }
        setState { copy(onboardingFlow = onboardingFlow) }

        val configUrl = loginConfig?.homeServerUrl?.takeIf { it.isNotEmpty() }
        if (configUrl != null) {
            // Use config from uri
            val homeServerConnectionConfig = homeServerConnectionConfigFactory.create(configUrl)
            if (homeServerConnectionConfig == null) {
                // Url is invalid, in this case, just use the regular flow
                Timber.w("Url from config url was invalid: $configUrl")
                continueToPageAfterSplash(onboardingFlow)
            } else {
                getLoginFlow(homeServerConnectionConfig, ServerType.Other)
            }
        } else {
            continueToPageAfterSplash(onboardingFlow)
        }
    }

    private fun continueToPageAfterSplash(onboardingFlow: OnboardingFlow) {
        val nextOnboardingStep = when (onboardingFlow) {
            OnboardingFlow.SignUp       -> if (vectorFeatures.isOnboardingUseCaseEnabled()) {
                OnboardingViewEvents.OpenUseCaseSelection
            } else {
                OnboardingViewEvents.OpenServerSelection
            }
            OnboardingFlow.SignIn,
            OnboardingFlow.SignInSignUp -> OnboardingViewEvents.OpenServerSelection
        }
        _viewEvents.post(nextOnboardingStep)
    }

    private fun handleUserAcceptCertificate(action: OnboardingAction.UserAcceptCertificate) {
        // It happens when we get the login flow, or during direct authentication.
        // So alter the homeserver config and retrieve again the login flow
        when (val finalLastAction = lastAction) {
            is OnboardingAction.UpdateHomeServer -> {
                currentHomeServerConnectionConfig
                        ?.let { it.copy(allowedFingerprints = it.allowedFingerprints + action.fingerprint) }
                        ?.let { getLoginFlow(it) }
            }
            is OnboardingAction.LoginOrRegister  ->
                handleDirectLogin(
                        finalLastAction,
                        HomeServerConnectionConfig.Builder()
                                // Will be replaced by the task
                                .withHomeServerUri("https://dummy.org")
                                .withAllowedFingerPrints(listOf(action.fingerprint))
                                .build()
                )
            else                                 -> Unit
        }
    }

    private fun rememberHomeServer(homeServerUrl: String) {
        homeServerHistoryService.addHomeServerToHistory(homeServerUrl)
        getKnownCustomHomeServersUrls()
    }

    private fun handleClearHomeServerHistory() {
        homeServerHistoryService.clearHistory()
        getKnownCustomHomeServersUrls()
    }

    private fun handleLoginWithToken(action: OnboardingAction.LoginWithToken) {
        val safeLoginWizard = loginWizard

        if (safeLoginWizard == null) {
            setState { copy(isLoading = false) }
            _viewEvents.post(OnboardingViewEvents.Failure(Throwable("Bad configuration")))
        } else {
            setState { copy(isLoading = true) }

            currentJob = viewModelScope.launch {
                try {
                    val result = safeLoginWizard.loginWithToken(action.loginToken)
                    onSessionCreated(result, isAccountCreated = false)
                } catch (failure: Throwable) {
                    setState { copy(isLoading = false) }
                    _viewEvents.post(OnboardingViewEvents.Failure(failure))
                }
            }
        }
    }

    private fun handleRegisterAction(action: RegisterAction) {
        currentJob = viewModelScope.launch {
            if (action.hasLoadingState()) {
                setState { copy(isLoading = true) }
            }
            runCatching { registrationActionHandler.handleRegisterAction(registrationWizard, action) }
                    .fold(
                            onSuccess = {
                                when {
                                    action.ignoresResult() -> {
                                        // do nothing
                                    }
                                    else                   -> when (it) {
                                        is RegistrationResult.Success      -> onSessionCreated(it.session, isAccountCreated = true)
                                        is RegistrationResult.FlowResponse -> onFlowResponse(it.flowResult)
                                    }
                                }
                            },
                            onFailure = {
                                if (it !is CancellationException) {
                                    _viewEvents.post(OnboardingViewEvents.Failure(it))
                                }
                            }
                    )
            setState { copy(isLoading = false) }
        }
    }

    private fun handleRegisterWith(action: OnboardingAction.Register) {
        reAuthHelper.data = action.password
        handleRegisterAction(RegisterAction.CreateAccount(
                action.username,
                action.password,
                action.initialDeviceName
        ))
    }

    private fun handleResetAction(action: OnboardingAction.ResetAction) {
        // Cancel any request
        currentJob = null

        when (action) {
            OnboardingAction.ResetHomeServerType -> {
                setState {
                    copy(
                            serverType = ServerType.Unknown
                    )
                }
            }
            OnboardingAction.ResetHomeServerUrl  -> {
                viewModelScope.launch {
                    authenticationService.reset()
                    setState {
                        copy(
                                isLoading = false,
                                homeServerUrlFromUser = null,
                                homeServerUrl = null,
                                loginMode = LoginMode.Unknown,
                                serverType = ServerType.Unknown,
                                loginModeSupportedTypes = emptyList()
                        )
                    }
                }
            }
            OnboardingAction.ResetSignMode              -> {
                setState {
                    copy(
                            isLoading = false,
                            signMode = SignMode.Unknown,
                            loginMode = LoginMode.Unknown,
                            loginModeSupportedTypes = emptyList()
                    )
                }
            }
            OnboardingAction.ResetAuthenticationAttempt -> {
                viewModelScope.launch {
                    authenticationService.cancelPendingLoginOrRegistration()
                    setState { copy(isLoading = false) }
                }
            }
            OnboardingAction.ResetResetPassword         -> {
                setState {
                    copy(
                            isLoading = false,
                            resetPasswordEmail = null
                    )
                }
            }
        }
    }

    private fun handleUpdateSignMode(action: OnboardingAction.UpdateSignMode) {
        setState {
            copy(
                    signMode = action.signMode
            )
        }

        when (action.signMode) {
            SignMode.SignUp             -> handleRegisterAction(RegisterAction.StartRegistration)
            SignMode.SignIn             -> startAuthenticationFlow()
            SignMode.SignInWithMatrixId -> _viewEvents.post(OnboardingViewEvents.OnSignModeSelected(SignMode.SignInWithMatrixId))
            SignMode.Unknown            -> Unit
        }
    }

    private fun handleUpdateUseCase(action: OnboardingAction.UpdateUseCase) {
        setState { copy(useCase = action.useCase) }
        when (vectorFeatures.isOnboardingCombinedChooseServerEnabled()) {
            true  -> {
                handle(OnboardingAction.UpdateHomeServer(matrixOrgUrl))
                OnboardingViewEvents.OpenCombinedServerSelection
            }
            false -> _viewEvents.post(OnboardingViewEvents.OpenServerSelection)
        }
    }

    private fun resetUseCase() {
        setState { copy(useCase = null) }
    }

    private fun handleUpdateServerType(action: OnboardingAction.UpdateServerType) {
        setState {
            copy(
                    serverType = action.serverType
            )
        }

        when (action.serverType) {
            ServerType.Unknown   -> Unit /* Should not happen */
            ServerType.MatrixOrg ->
                // Request login flow here
                handle(OnboardingAction.UpdateHomeServer(matrixOrgUrl))
            ServerType.EMS,
            ServerType.Other     -> _viewEvents.post(OnboardingViewEvents.OnServerSelectionDone(action.serverType))
        }
    }

    private fun handleInitWith(action: OnboardingAction.InitWith) {
        loginConfig = action.loginConfig

        // If there is a pending email validation continue on this step
        try {
            if (registrationWizard.isRegistrationStarted) {
                currentThreePid?.let {
                    handle(OnboardingAction.PostViewEvent(OnboardingViewEvents.OnSendEmailSuccess(it)))
                }
            }
        } catch (e: Throwable) {
            // NOOP. API is designed to use wizards in a login/registration flow,
            // but we need to check the state anyway.
        }
    }

    private fun handleResetPassword(action: OnboardingAction.ResetPassword) {
        val safeLoginWizard = loginWizard

        if (safeLoginWizard == null) {
            setState { copy(isLoading = false) }
            _viewEvents.post(OnboardingViewEvents.Failure(Throwable("Bad configuration")))
        } else {
            setState { copy(isLoading = true) }

            currentJob = viewModelScope.launch {
                try {
                    safeLoginWizard.resetPassword(action.email, action.newPassword)
                } catch (failure: Throwable) {
                    setState { copy(isLoading = false) }
                    _viewEvents.post(OnboardingViewEvents.Failure(failure))
                    return@launch
                }

                setState {
                    copy(
                            isLoading = false,
                            resetPasswordEmail = action.email
                    )
                }

                _viewEvents.post(OnboardingViewEvents.OnResetPasswordSendThreePidDone)
            }
        }
    }

    private fun handleResetPasswordMailConfirmed() {
        val safeLoginWizard = loginWizard

        if (safeLoginWizard == null) {
            setState { copy(isLoading = false) }
            _viewEvents.post(OnboardingViewEvents.Failure(Throwable("Bad configuration")))
        } else {
            setState { copy(isLoading = false) }

            currentJob = viewModelScope.launch {
                try {
                    safeLoginWizard.resetPasswordMailConfirmed()
                } catch (failure: Throwable) {
                    setState { copy(isLoading = false) }
                    _viewEvents.post(OnboardingViewEvents.Failure(failure))
                    return@launch
                }
                setState {
                    copy(
                            isLoading = false,
                            resetPasswordEmail = null
                    )
                }

                _viewEvents.post(OnboardingViewEvents.OnResetPasswordMailConfirmationSuccess)
            }
        }
    }

    private fun handleLoginOrRegister(action: OnboardingAction.LoginOrRegister) = withState { state ->
        when (state.signMode) {
            SignMode.Unknown            -> error("Developer error, invalid sign mode")
            SignMode.SignIn             -> handleLogin(action)
            SignMode.SignUp             -> handleRegisterWith(OnboardingAction.Register(action.username, action.password, action.initialDeviceName))
            SignMode.SignInWithMatrixId -> handleDirectLogin(action, null)
        }
    }

    private fun handleDirectLogin(action: OnboardingAction.LoginOrRegister, homeServerConnectionConfig: HomeServerConnectionConfig?) {
        setState { copy(isLoading = true) }

        currentJob = viewModelScope.launch {
            val data = try {
                authenticationService.getWellKnownData(action.username, homeServerConnectionConfig)
            } catch (failure: Throwable) {
                onDirectLoginError(failure)
                return@launch
            }
            when (data) {
                is WellknownResult.Prompt     ->
                    directLoginOnWellknownSuccess(action, data, homeServerConnectionConfig)
                is WellknownResult.FailPrompt ->
                    // Relax on IS discovery if homeserver is valid
                    if (data.homeServerUrl != null && data.wellKnown != null) {
                        directLoginOnWellknownSuccess(action, WellknownResult.Prompt(data.homeServerUrl!!, null, data.wellKnown!!), homeServerConnectionConfig)
                    } else {
                        onWellKnownError()
                    }
                else                          -> {
                    onWellKnownError()
                }
            }
        }
    }

    private fun onWellKnownError() {
        setState { copy(isLoading = false) }
        _viewEvents.post(OnboardingViewEvents.Failure(Exception(stringProvider.getString(R.string.autodiscover_well_known_error))))
    }

    private suspend fun directLoginOnWellknownSuccess(action: OnboardingAction.LoginOrRegister,
                                                      wellKnownPrompt: WellknownResult.Prompt,
                                                      homeServerConnectionConfig: HomeServerConnectionConfig?) {
        val alteredHomeServerConnectionConfig = homeServerConnectionConfig
                ?.copy(
                        homeServerUriBase = Uri.parse(wellKnownPrompt.homeServerUrl),
                        identityServerUri = wellKnownPrompt.identityServerUrl?.let { Uri.parse(it) }
                )
                ?: HomeServerConnectionConfig(
                        homeServerUri = Uri.parse("https://${action.username.getDomain()}"),
                        homeServerUriBase = Uri.parse(wellKnownPrompt.homeServerUrl),
                        identityServerUri = wellKnownPrompt.identityServerUrl?.let { Uri.parse(it) }
                )

        val data = try {
            authenticationService.directAuthentication(
                    alteredHomeServerConnectionConfig,
                    action.username,
                    action.password,
                    action.initialDeviceName)
        } catch (failure: Throwable) {
            onDirectLoginError(failure)
            return
        }
        onSessionCreated(data, isAccountCreated = true)
    }

    private fun onDirectLoginError(failure: Throwable) {
        when (failure) {
            is MatrixIdFailure.InvalidMatrixId,
            is Failure.UnrecognizedCertificateFailure -> {
                setState { copy(isLoading = false) }
                // Display this error in a dialog
                _viewEvents.post(OnboardingViewEvents.Failure(failure))
            }
            else                                      -> {
                setState { copy(isLoading = false) }
            }
        }
    }

    private fun handleLogin(action: OnboardingAction.LoginOrRegister) {
        val safeLoginWizard = loginWizard

        if (safeLoginWizard == null) {
            setState { copy(isLoading = false) }
            _viewEvents.post(OnboardingViewEvents.Failure(Throwable("Bad configuration")))
        } else {
            setState { copy(isLoading = true) }
            currentJob = viewModelScope.launch {
                try {
                    val result = safeLoginWizard.login(
                            action.username,
                            action.password,
                            action.initialDeviceName
                    )
                    reAuthHelper.data = action.password
                    onSessionCreated(result, isAccountCreated = false)
                } catch (failure: Throwable) {
                    setState { copy(isLoading = false) }
                    _viewEvents.post(OnboardingViewEvents.Failure(failure))
                }
            }
        }
    }

    private fun startAuthenticationFlow() {
        // Ensure Wizard is ready
        loginWizard

        _viewEvents.post(OnboardingViewEvents.OnSignModeSelected(SignMode.SignIn))
    }

    private fun onFlowResponse(flowResult: FlowResult) {
        // If dummy stage is mandatory, and password is already sent, do the dummy stage now
        if (isRegistrationStarted && flowResult.missingStages.any { it is Stage.Dummy && it.mandatory }) {
            handleRegisterDummy()
        } else {
            // Notify the user
            _viewEvents.post(OnboardingViewEvents.RegistrationFlowResult(flowResult, isRegistrationStarted))
        }
    }

    private fun handleRegisterDummy() {
        handleRegisterAction(RegisterAction.RegisterDummy)
    }

    private suspend fun onSessionCreated(session: Session, isAccountCreated: Boolean) {
        val state = awaitState()
        state.useCase?.let { useCase ->
            session.vectorStore(applicationContext).setUseCase(useCase)
            analyticsTracker.updateUserProperties(UserProperties(ftueUseCaseSelection = useCase.toTrackingValue()))
        }
        activeSessionHolder.setActiveSession(session)

        authenticationService.reset()
        session.configureAndStart(applicationContext)

        when (isAccountCreated) {
            true  -> {
                val personalizationState = createPersonalizationState(session, state)
                setState {
                    copy(isLoading = false, personalizationState = personalizationState)
                }
                _viewEvents.post(OnboardingViewEvents.OnAccountCreated)
            }
            false -> {
                setState { copy(isLoading = false) }
                _viewEvents.post(OnboardingViewEvents.OnAccountSignedIn)
            }
        }
    }

    private suspend fun createPersonalizationState(session: Session, state: OnboardingViewState): PersonalizationState {
        return when {
            vectorFeatures.isOnboardingPersonalizeEnabled() -> {
                val homeServerCapabilities = session.getHomeServerCapabilities()
                val capabilityOverrides = vectorOverrides.forceHomeserverCapabilities?.firstOrNull()
                state.personalizationState.copy(
                        supportsChangingDisplayName = capabilityOverrides?.canChangeDisplayName ?: homeServerCapabilities.canChangeDisplayName,
                        supportsChangingProfilePicture = capabilityOverrides?.canChangeAvatar ?: homeServerCapabilities.canChangeAvatar
                )
            }
            else                                            -> state.personalizationState
        }
    }

    private fun handleWebLoginSuccess(action: OnboardingAction.WebLoginSuccess) = withState { state ->
        val homeServerConnectionConfigFinal = homeServerConnectionConfigFactory.create(state.homeServerUrl)

        if (homeServerConnectionConfigFinal == null) {
            // Should not happen
            Timber.w("homeServerConnectionConfig is null")
        } else {
            currentJob = viewModelScope.launch {
                try {
                    val result = authenticationService.createSessionFromSso(homeServerConnectionConfigFinal, action.credentials)
                    onSessionCreated(result, isAccountCreated = false)
                } catch (failure: Throwable) {
                    setState { copy(isLoading = false) }
                }
            }
        }
    }

    private fun handleUpdateHomeserver(action: OnboardingAction.UpdateHomeServer) {
        val homeServerConnectionConfig = homeServerConnectionConfigFactory.create(action.homeServerUrl)
        if (homeServerConnectionConfig == null) {
            // This is invalid
            _viewEvents.post(OnboardingViewEvents.Failure(Throwable("Unable to create a HomeServerConnectionConfig")))
        } else {
            getLoginFlow(homeServerConnectionConfig)
        }
    }

    private fun getLoginFlow(homeServerConnectionConfig: HomeServerConnectionConfig,
                             serverTypeOverride: ServerType? = null) {
        currentHomeServerConnectionConfig = homeServerConnectionConfig

        currentJob = viewModelScope.launch {
            authenticationService.cancelPendingLoginOrRegistration()

            setState {
                copy(
                        isLoading = true,
                        // If user has entered https://matrix.org, ensure that server type is ServerType.MatrixOrg
                        // It is also useful to set the value again in the case of a certificate error on matrix.org
                        serverType = if (homeServerConnectionConfig.homeServerUri.toString() == matrixOrgUrl) {
                            ServerType.MatrixOrg
                        } else {
                            serverTypeOverride ?: serverType
                        }
                )
            }

            val data = try {
                authenticationService.getLoginFlow(homeServerConnectionConfig)
            } catch (failure: Throwable) {
                setState {
                    copy(
                            isLoading = false,
                            // If we were trying to retrieve matrix.org login flow, also reset the serverType
                            serverType = if (serverType == ServerType.MatrixOrg) ServerType.Unknown else serverType
                    )
                }
                _viewEvents.post(OnboardingViewEvents.Failure(failure))
                null
            }

            data ?: return@launch

            // Valid Homeserver, add it to the history.
            // Note: we add what the user has input, data.homeServerUrlBase can be different
            rememberHomeServer(homeServerConnectionConfig.homeServerUri.toString())

            val loginMode = when {
                // SSO login is taken first
                data.supportedLoginTypes.contains(LoginFlowTypes.SSO) &&
                        data.supportedLoginTypes.contains(LoginFlowTypes.PASSWORD) -> LoginMode.SsoAndPassword(data.ssoIdentityProviders)
                data.supportedLoginTypes.contains(LoginFlowTypes.SSO)              -> LoginMode.Sso(data.ssoIdentityProviders)
                data.supportedLoginTypes.contains(LoginFlowTypes.PASSWORD)         -> LoginMode.Password
                else                                                               -> LoginMode.Unsupported
            }

            setState {
                copy(
                        isLoading = false,
                        homeServerUrlFromUser = homeServerConnectionConfig.homeServerUri.toString(),
                        homeServerUrl = data.homeServerUrl,
                        loginMode = loginMode,
                        loginModeSupportedTypes = data.supportedLoginTypes.toList()
                )
            }
            if ((loginMode == LoginMode.Password && !data.isLoginAndRegistrationSupported) ||
                    data.isOutdatedHomeserver) {
                // Notify the UI
                _viewEvents.post(OnboardingViewEvents.OutdatedHomeserver)
            }

            withState {
                if (loginMode.supportsSignModeScreen()) {
                    when (it.onboardingFlow) {
                        OnboardingFlow.SignIn -> handleUpdateSignMode(OnboardingAction.UpdateSignMode(SignMode.SignIn))
                        OnboardingFlow.SignUp -> handleUpdateSignMode(OnboardingAction.UpdateSignMode(SignMode.SignUp))
                        OnboardingFlow.SignInSignUp,
                        null                  -> {
                            _viewEvents.post(OnboardingViewEvents.OnLoginFlowRetrieved)
                        }
                    }
                } else {
                    _viewEvents.post(OnboardingViewEvents.OnLoginFlowRetrieved)
                }
            }
        }
    }

    fun getInitialHomeServerUrl(): String? {
        return loginConfig?.homeServerUrl
    }

    fun getSsoUrl(redirectUrl: String, deviceId: String?, providerId: String?): String? {
        return authenticationService.getSsoUrl(redirectUrl, deviceId, providerId)
    }

    fun getFallbackUrl(forSignIn: Boolean, deviceId: String?): String? {
        return authenticationService.getFallbackUrl(forSignIn, deviceId)
    }

    private fun updateDisplayName(displayName: String) {
        setState { copy(isLoading = true) }
        viewModelScope.launch {
            val activeSession = activeSessionHolder.getActiveSession()
            try {
                activeSession.setDisplayName(activeSession.myUserId, displayName)
                setState {
                    copy(
                            isLoading = false,
                            personalizationState = personalizationState.copy(displayName = displayName)
                    )
                }
                handleDisplayNameStepComplete()
            } catch (error: Throwable) {
                setState { copy(isLoading = false) }
                _viewEvents.post(OnboardingViewEvents.Failure(error))
            }
        }
    }

    private fun handlePersonalizeProfile() {
        withPersonalisationState {
            when {
                it.supportsChangingDisplayName    -> _viewEvents.post(OnboardingViewEvents.OnChooseDisplayName)
                it.supportsChangingProfilePicture -> _viewEvents.post(OnboardingViewEvents.OnChooseProfilePicture)
                else                              -> {
                    throw IllegalStateException("It should not be possible to personalize without supporting display name or avatar changing")
                }
            }
        }
    }

    private fun handleDisplayNameStepComplete() {
        withPersonalisationState {
            when {
                it.supportsChangingProfilePicture -> _viewEvents.post(OnboardingViewEvents.OnChooseProfilePicture)
                else                              -> completePersonalization()
            }
        }
    }

    private fun handleProfilePictureSelected(action: OnboardingAction.ProfilePictureSelected) {
        setState {
            copy(personalizationState = personalizationState.copy(selectedPictureUri = action.uri))
        }
    }

    private fun withPersonalisationState(block: (PersonalizationState) -> Unit) {
        withState { block(it.personalizationState) }
    }

    private fun updateProfilePicture() {
        withState { state ->
            when (val pictureUri = state.personalizationState.selectedPictureUri) {
                null -> _viewEvents.post(OnboardingViewEvents.Failure(NullPointerException("picture uri is missing from state")))
                else -> {
                    setState { copy(isLoading = true) }
                    viewModelScope.launch {
                        val activeSession = activeSessionHolder.getActiveSession()
                        try {
                            activeSession.updateAvatar(
                                    activeSession.myUserId,
                                    pictureUri,
                                    uriFilenameResolver.getFilenameFromUri(pictureUri) ?: UUID.randomUUID().toString()
                            )
                            setState {
                                copy(
                                        isLoading = false,
                                )
                            }
                            onProfilePictureSaved()
                        } catch (error: Throwable) {
                            setState { copy(isLoading = false) }
                            _viewEvents.post(OnboardingViewEvents.Failure(error))
                        }
                    }
                }
            }
        }
    }

    private fun onProfilePictureSaved() {
        completePersonalization()
    }

    private fun completePersonalization() {
        _viewEvents.post(OnboardingViewEvents.OnPersonalizationComplete)
    }

    private fun cancelWaitForEmailValidation() {
        currentJob = null
    }
}

private fun LoginMode.supportsSignModeScreen(): Boolean {
    return when (this) {
        LoginMode.Password,
        is LoginMode.SsoAndPassword -> true
        is LoginMode.Sso,
        LoginMode.Unknown,
        LoginMode.Unsupported       -> false
    }
}
