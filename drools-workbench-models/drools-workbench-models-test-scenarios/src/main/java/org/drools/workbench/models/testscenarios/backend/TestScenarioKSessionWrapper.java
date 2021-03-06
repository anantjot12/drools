/*
 * Copyright 2012 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.workbench.models.testscenarios.backend;

import java.lang.reflect.InvocationTargetException;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.drools.core.base.TypeResolver;
import org.drools.core.common.InternalAgenda;
import org.drools.core.common.InternalAgendaGroup;
import org.drools.core.time.impl.PseudoClockScheduler;
import org.drools.workbench.models.testscenarios.backend.executors.MethodExecutor;
import org.drools.workbench.models.testscenarios.backend.verifiers.FactVerifier;
import org.drools.workbench.models.testscenarios.backend.verifiers.RuleFiredVerifier;
import org.drools.workbench.models.testscenarios.shared.CallMethod;
import org.drools.workbench.models.testscenarios.shared.ExecutionTrace;
import org.drools.workbench.models.testscenarios.shared.Expectation;
import org.drools.workbench.models.testscenarios.shared.VerifyFact;
import org.drools.workbench.models.testscenarios.shared.VerifyRuleFired;
import org.kie.api.runtime.KieSession;

public class TestScenarioKSessionWrapper {

    private final KieSession ksession;
    private final FactVerifier factVerifier;
    private final RuleFiredVerifier ruleFiredVerifier = new RuleFiredVerifier();

    private TestingEventListener eventListener = null;
    private final MethodExecutor methodExecutor;
    private final Map<String, Object> populatedData;

    private final ClassLoader classLoader;

    public TestScenarioKSessionWrapper( KieSession ksession,
                                        final TypeResolver resolver,
                                        final ClassLoader classLoader,
                                        Map<String, Object> populatedData,
                                        Map<String, Object> globalData ) {
        this.ksession = ksession;
        this.populatedData = populatedData;
        this.methodExecutor = new MethodExecutor( populatedData );
        this.classLoader = classLoader;

        factVerifier = initFactVerifier( resolver,
                                         globalData );
    }

    private FactVerifier initFactVerifier( TypeResolver resolver,
                                           Map<String, Object> globalData ) {
        return new FactVerifier( populatedData,
                                 resolver,
                                 classLoader,
                                 ksession,
                                 globalData );
    }

    public void activateRuleFlowGroup( String activateRuleFlowGroupName ) {
        // mark does not want to make the following methods public, so for now we have to downcast
        ((InternalAgendaGroup)ksession.getAgenda().getRuleFlowGroup( activateRuleFlowGroupName )).setAutoDeactivate( false );
        // same for the following method
        ( (InternalAgenda) ksession.getAgenda() ).activateRuleFlowGroup( activateRuleFlowGroupName );
    }

    public void verifyExpectation( Expectation expectation ) throws InvocationTargetException,
            NoSuchMethodException,
            IllegalAccessException,
            InstantiationException {
        if ( expectation instanceof VerifyFact ) {
            factVerifier.verify( (VerifyFact) expectation );
        } else if ( expectation instanceof VerifyRuleFired ) {
            ruleFiredVerifier.verifyFiringCounts( (VerifyRuleFired) expectation );
        }
    }

    public void executeMethod( CallMethod callMethod ) {
        methodExecutor.executeMethod( callMethod );
    }

    private void fireAllRules( ScenarioSettings scenarioSettings ) {
        this.ksession.fireAllRules( eventListener.getAgendaFilter( scenarioSettings.getRuleList(),
                                                                   scenarioSettings.isInclusive() ),
                                    scenarioSettings.getMaxRuleFirings() );
    }

    private void resetEventListener() {
        if ( eventListener != null ) {
            this.ksession.removeEventListener( eventListener ); //remove the old
        }
        eventListener = new TestingEventListener();
        this.ksession.addEventListener( eventListener );
        this.ruleFiredVerifier.setFireCounter( eventListener.getFiringCounts() );
    }

    public void executeSubScenario( ExecutionTrace executionTrace,
                                    ScenarioSettings scenarioSettings ) {

        resetEventListener();

        //set up the time machine
        applyTimeMachine( executionTrace );

        long startTime = System.currentTimeMillis();

        fireAllRules( scenarioSettings );

        executionTrace.setExecutionTimeResult( System.currentTimeMillis() - startTime );
        executionTrace.setNumberOfRulesFired( eventListener.totalFires );
        executionTrace.setRulesFired( eventListener.getRulesFiredSummary() );
    }

    private void applyTimeMachine( ExecutionTrace executionTrace ) {
        ( (PseudoClockScheduler) ksession.getSessionClock() ).advanceTime( getTargetTime( executionTrace ) - getCurrentTime(),
                                                                           TimeUnit.MILLISECONDS );
    }

    private long getTargetTime( ExecutionTrace executionTrace ) {
        if ( executionTrace.getScenarioSimulatedDate() != null ) {
            return executionTrace.getScenarioSimulatedDate().getTime();
        } else {
            return new Date().getTime();
        }
    }

    private long getCurrentTime() {
        return ksession.getSessionClock().getCurrentTime();
    }
}
