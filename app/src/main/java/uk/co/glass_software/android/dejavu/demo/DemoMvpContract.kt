/*
 * Copyright (C) 2017 Glass Software Ltd
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package uk.co.glass_software.android.dejavu.demo

import dagger.Component
import uk.co.glass_software.android.boilerplate.core.mvp.base.MvpContract.*
import uk.co.glass_software.android.boilerplate.core.utils.lambda.Callback1
import uk.co.glass_software.android.boilerplate.core.utils.log.Logger
import uk.co.glass_software.android.dejavu.configuration.CacheInstruction
import uk.co.glass_software.android.dejavu.demo.injection.DemoViewModule
import uk.co.glass_software.android.dejavu.demo.model.CatFactResponse
import uk.co.glass_software.android.dejavu.demo.presenter.CompositePresenter.Method
import javax.inject.Named
import javax.inject.Singleton

internal class DemoMvpContract {

    interface DemoMvpView : MvpView<DemoMvpView, DemoPresenter, DemoViewComponent> {

        fun showCatFact(response: CatFactResponse)
        fun onCallStarted()
        fun onCallComplete()

    }

    interface DemoPresenter : Presenter<DemoMvpView, DemoPresenter, DemoViewComponent> {

        var useSingle: Boolean
        var allowNonFinalForSingle: Boolean
        var encrypt: Boolean
        var compress: Boolean
        var freshOnly: Boolean
        var connectivityTimeoutOn: Boolean

        fun loadCatFact(isRefresh: Boolean)
        fun clearEntries()
        fun invalidate()
        fun offline()
        fun getCacheInstruction(): CacheInstruction

    }

    @Singleton
    @Component(modules = [DemoViewModule::class])
    interface DemoViewComponent : ViewComponent<DemoMvpView, DemoPresenter, DemoViewComponent> {

        fun presenterSwitcher(): Callback1<Method>

        @Named("boilerplate")
        fun logger(): Logger
    }

}