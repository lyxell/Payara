/*
    DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.

    Copyright (c) [2019] Payara Foundation and/or its affiliates. All rights reserved.

    The contents of this file are subject to the terms of either the GNU
    General Public License Version 2 only ("GPL") or the Common Development
    and Distribution License("CDDL") (collectively, the "License").  You
    may not use this file except in compliance with the License.  You can
    obtain a copy of the License at
    https://github.com/payara/Payara/blob/master/LICENSE.txt
    See the License for the specific
    language governing permissions and limitations under the License.

    When distributing the software, include this License Header Notice in each
    file and include the License file at glassfish/legal/LICENSE.txt.

    GPL Classpath Exception:
    The Payara Foundation designates this particular file as subject to the "Classpath"
    exception as provided by the Payara Foundation in the GPL Version 2 section of the License
    file that accompanied this code.

    Modifications:
    If applicable, add the following below the License Header, with the fields
    enclosed by brackets [] replaced by your own identifying information:
    "Portions Copyright [year] [name of copyright owner]"

    Contributor(s):
    If you wish your version of this file to be governed by only the CDDL or
    only the GPL Version 2, indicate your decision by adding "[Contributor]
    elects to include this software in this distribution under the [CDDL or GPL
    Version 2] license."  If you don't indicate a single choice of license, a
    recipient has the option to distribute your version of this file under
    either the CDDL, the GPL Version 2 or to extend the choice of license to
    its licensees as provided above.  However, if you add GPL Version 2 code
    and therefore, elected the GPL Version 2 license, then the option applies
    only if the new code is made subject to such option by the copyright
    holder.
 */
package fish.payara.admin.monitor.cli;

import com.sun.enterprise.config.serverbeans.Config;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.MonitoringService;
import com.sun.enterprise.util.ColumnFormatter;
import com.sun.enterprise.util.StringUtils;
import com.sun.enterprise.util.SystemPropertyConstants;
import fish.payara.admin.amx.config.AMXConfiguration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import javax.inject.Inject;
import org.glassfish.api.ActionReport;
import org.glassfish.api.I18n;
import org.glassfish.api.Param;
import org.glassfish.api.admin.AdminCommand;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.api.admin.CommandLock;
import org.glassfish.api.admin.CommandRunner;
import org.glassfish.api.admin.ExecuteOn;
import org.glassfish.api.admin.RestEndpoint;
import org.glassfish.api.admin.RestEndpoints;
import org.glassfish.api.admin.RuntimeType;
import org.glassfish.config.support.CommandTarget;
import org.glassfish.config.support.TargetType;
import org.glassfish.hk2.api.PerLookup;
import org.glassfish.internal.api.Target;
import org.jvnet.hk2.annotations.Service;

/**
 * @author Susan Rai
 */
@Service(name = "get-monitoring-configuration")
@PerLookup
@CommandLock(CommandLock.LockType.NONE)
@I18n("get.monitoring.configuration")
@ExecuteOn(value = {RuntimeType.DAS})
@TargetType(value = {CommandTarget.DAS, CommandTarget.STANDALONE_INSTANCE, CommandTarget.CLUSTER, CommandTarget.CLUSTERED_INSTANCE, CommandTarget.CONFIG, CommandTarget.DEPLOYMENT_GROUP})
@RestEndpoints({
    @RestEndpoint(configBean = Domain.class,
            opType = RestEndpoint.OpType.GET,
            path = "get-monitoring-configuration",
            description = "List Payara Monitoring Service Configuration")
})
public class GetMonitoringConfiguration implements AdminCommand {

    private static final Logger logger = Logger.getLogger(SetMonitoringConfiguration.class.getName());
    @Inject
    private Target targetUtil;

    @Param(name = "target", optional = true, defaultValue = SystemPropertyConstants.DAS_SERVER_NAME)
    String target;

    @Inject
    private CommandRunner commandRunner;

    @Override
    public void execute(AdminCommandContext context) {
        ActionReport actionReport = context.getActionReport();
        ActionReport monitoringServiceReport = actionReport.addSubActionsReport();
         
        Config config = targetUtil.getConfig(target);
        if (config == null) {
            actionReport.setMessage("No such config named: " + target);
            actionReport.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return;
        }
        MonitoringService monitoringService = config.getMonitoringService();
        AMXConfiguration amxConfiguration = config.getExtensionByType(AMXConfiguration.class);

        CommandRunner.CommandInvocation commandInvocation = commandRunner.getCommandInvocation("get-rest-monitoring-configuration", actionReport, context.getSubject());
        commandInvocation.execute();

        commandInvocation = commandRunner.getCommandInvocation("get-jmx-monitoring-configuration", actionReport, context.getSubject());
        commandInvocation.execute();   

        final String[] headers = {"Monitoring Enabled", "AMX Enabled", "MBeans Enabled", "DTrace Enabled"};

        ColumnFormatter columnFormatter = new ColumnFormatter(headers);
        columnFormatter.addRow(new Object[]{monitoringService.getMonitoringEnabled(), amxConfiguration.getEnabled(),
            monitoringService.getMbeanEnabled(), monitoringService.getDtraceEnabled()});

        Map<String, Object> extraPropertiesMap = new HashMap<>();
        extraPropertiesMap.put("monitoringEnabled", monitoringService.getMonitoringEnabled());
        extraPropertiesMap.put("mbeanEnabled", monitoringService.getMbeanEnabled());
        extraPropertiesMap.put("dtraceEnabled", monitoringService.getDtraceEnabled());
        extraPropertiesMap.put("amxEnabled", amxConfiguration.getEnabled());

        Properties extraProperties = new Properties();
        extraProperties.put("getMonitoringConfiguration", extraPropertiesMap);
        actionReport.setExtraProperties(extraProperties);
        monitoringServiceReport.setMessage(columnFormatter.toString());
        monitoringServiceReport.appendMessage(StringUtils.EOL);
        actionReport.setActionExitCode(ActionReport.ExitCode.SUCCESS);
    }

}
