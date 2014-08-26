/*
 * Password Management Servlets (PWM)
 * http://code.google.com/p/pwm/
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2014 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.ldap;

import com.novell.ldapchai.ChaiConstant;
import com.novell.ldapchai.ChaiFactory;
import com.novell.ldapchai.ChaiUser;
import com.novell.ldapchai.cr.Answer;
import com.novell.ldapchai.exception.ChaiOperationException;
import com.novell.ldapchai.exception.ChaiUnavailableException;
import com.novell.ldapchai.provider.ChaiConfiguration;
import com.novell.ldapchai.provider.ChaiProvider;
import com.novell.ldapchai.provider.ChaiProviderFactory;
import com.novell.ldapchai.provider.ChaiSetting;
import password.pwm.AppProperty;
import password.pwm.PwmApplication;
import password.pwm.bean.SessionLabel;
import password.pwm.bean.UserIdentity;
import password.pwm.config.Configuration;
import password.pwm.config.LdapProfile;
import password.pwm.config.PwmSetting;
import password.pwm.error.ErrorInformation;
import password.pwm.error.PwmError;
import password.pwm.error.PwmOperationalException;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.util.PwmLogger;
import password.pwm.util.PwmRandom;
import password.pwm.util.X509Utils;
import password.pwm.util.stats.Statistic;
import password.pwm.util.stats.StatisticsManager;

import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.util.*;

public class LdapOperationsHelper {
    private static final PwmLogger LOGGER = PwmLogger.getLogger(LdapOperationsHelper.class);

    public static void addConfiguredUserObjectClass(
            final UserIdentity userIdentity,
            final PwmApplication pwmApplication
    )
            throws ChaiUnavailableException, PwmUnrecoverableException {
        final LdapProfile ldapProfile = pwmApplication.getConfig().getLdapProfiles().get(userIdentity.getLdapProfileID());
        final Set<String> newObjClasses = new HashSet<>(ldapProfile.readSettingAsStringArray(PwmSetting.AUTO_ADD_OBJECT_CLASSES));
        if (newObjClasses.isEmpty()) {
            return;
        }
        final ChaiProvider chaiProvider = pwmApplication.getProxyChaiProvider(userIdentity.getLdapProfileID());
        final ChaiUser theUser = ChaiFactory.createChaiUser(userIdentity.getUserDN(), chaiProvider);
        addUserObjectClass(theUser, newObjClasses);
    }

    private static void addUserObjectClass(final ChaiUser theUser, final Set<String> newObjClasses)
            throws ChaiUnavailableException {
        String auxClass = null;
        try {
            final Set<String> existingObjClasses = theUser.readMultiStringAttribute(ChaiConstant.ATTR_LDAP_OBJECTCLASS);
            newObjClasses.removeAll(existingObjClasses);

            for (final String newObjClass : newObjClasses) {
                auxClass = newObjClass;
                theUser.addAttribute(ChaiConstant.ATTR_LDAP_OBJECTCLASS, auxClass);
                LOGGER.info("added objectclass '" + auxClass + "' to user " + theUser.getEntryDN());
            }
        } catch (ChaiOperationException e) {
            final StringBuilder errorMsg = new StringBuilder();

            errorMsg.append("error adding objectclass '").append(auxClass).append("' to user ");
            errorMsg.append(theUser.getEntryDN());
            errorMsg.append(": ");
            errorMsg.append(e.toString());

            LOGGER.error(errorMsg.toString());
        }
    }

    public static ChaiProvider openProxyChaiProvider(final LdapProfile ldapProfile, final Configuration config, final StatisticsManager statsMangager)
            throws PwmUnrecoverableException
    {
        final StringBuilder debugLogText = new StringBuilder();
        debugLogText.append("opening new ldap proxy connection");
        LOGGER.trace(debugLogText.toString());

        final String proxyDN = ldapProfile.readSettingAsString(PwmSetting.LDAP_PROXY_USER_DN);
        final String proxyPW = ldapProfile.readSettingAsString(PwmSetting.LDAP_PROXY_USER_PASSWORD);

        try {
            return createChaiProvider(ldapProfile, config, proxyDN, proxyPW);
        } catch (ChaiUnavailableException e) {
            if (statsMangager != null) {
                statsMangager.incrementValue(Statistic.LDAP_UNAVAILABLE_COUNT);
            }
            final StringBuilder errorMsg = new StringBuilder();
            errorMsg.append(" error connecting as proxy user: ");
            final PwmError pwmError = PwmError.forChaiError(e.getErrorCode());
            if (pwmError != null && pwmError != PwmError.ERROR_UNKNOWN) {
                errorMsg.append(new ErrorInformation(pwmError,e.getMessage()).toDebugStr());
            } else {
                errorMsg.append(e.getMessage());
            }
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_DIRECTORY_UNAVAILABLE,errorMsg.toString());
            LOGGER.fatal("check ldap proxy settings: " + errorInformation.toDebugStr());
            throw new PwmUnrecoverableException(errorInformation);
        }
    }


    public static String readLdapGuidValue(
            final PwmApplication pwmApplication,
            final SessionLabel sessionLabel,
            final UserIdentity userIdentity,
            final boolean throwExceptionOnError
    )
            throws ChaiUnavailableException, PwmUnrecoverableException
    {
        final String existingValue = GUIDHelper.readExistingGuidValue(pwmApplication, userIdentity, throwExceptionOnError);
        final LdapProfile ldapProfile = pwmApplication.getConfig().getLdapProfiles().get(
                userIdentity.getLdapProfileID());
        final String guidAttributeName = ldapProfile.readSettingAsString(PwmSetting.LDAP_GUID_ATTRIBUTE);
        if (existingValue == null || existingValue.length() < 1) {
            if (!"DN".equalsIgnoreCase(guidAttributeName) && !"VENDORGUID".equalsIgnoreCase(guidAttributeName)) {
                if (ldapProfile.readSettingAsBoolean(PwmSetting.LDAP_GUID_AUTO_ADD)) {
                    LOGGER.trace("assigning new GUID to user " + userIdentity);
                    return GUIDHelper.assignGuidToUser(pwmApplication, sessionLabel, userIdentity, guidAttributeName);
                }
            }
            final String errorMsg = "unable to resolve GUID value for user " + userIdentity.toString();
            GUIDHelper.processError(errorMsg,throwExceptionOnError);
        }
        return existingValue;
    }

    private static class GUIDHelper {
        private static String readExistingGuidValue(
                final PwmApplication pwmApplication,
                final UserIdentity userIdentity,
                final boolean throwExceptionOnError
        )
                throws ChaiUnavailableException, PwmUnrecoverableException
        {
            final ChaiUser theUser = pwmApplication.getProxiedChaiUser(userIdentity);
            final LdapProfile ldapProfile = pwmApplication.getConfig().getLdapProfiles().get(userIdentity.getLdapProfileID());
            final String guidAttributeName = ldapProfile.readSettingAsString(PwmSetting.LDAP_GUID_ATTRIBUTE);

            if ("DN".equalsIgnoreCase(guidAttributeName)) {
                return userIdentity.toDeliminatedKey();
            }

            if ("VENDORGUID".equalsIgnoreCase(guidAttributeName)) {
                try {
                    final String guidValue = theUser.readGUID();
                    if (guidValue != null && guidValue.length() > 1) {
                        LOGGER.trace("read VENDORGUID value for user " + theUser + ": " + guidValue);
                    } else {
                        LOGGER.trace("unable to find a VENDORGUID value for user " + theUser.getEntryDN());
                    }
                    return guidValue;
                } catch (Exception e) {
                    final String errorMsg = "error while reading vendor GUID value for user " + theUser.getEntryDN() + ", error: " + e.getMessage();
                    return processError(errorMsg, throwExceptionOnError);
                }
            }

            try {
                return theUser.readStringAttribute(guidAttributeName);
            } catch (ChaiOperationException e) {
                final String errorMsg = "unexpected error while reading attribute GUID value for user " + userIdentity + " from '" + guidAttributeName + "', error: " + e.getMessage();
                return processError(errorMsg, throwExceptionOnError);
            }
        }

        private static String processError(final String errorMsg, final boolean throwExceptionOnError)
                throws PwmUnrecoverableException
        {
            final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_MISSING_GUID,errorMsg);
            if (throwExceptionOnError) {
                throw new PwmUnrecoverableException(errorInformation);
            }
            LOGGER.warn(errorMsg);
            return null;
        }

        private static boolean searchForExistingGuidValue(
                final PwmApplication pwmApplication,
                final SessionLabel sessionLabel,
                final String guidValue
        )
                throws ChaiUnavailableException, PwmUnrecoverableException
        {
            boolean exists = false;
            for (final LdapProfile ldapProfile : pwmApplication.getConfig().getLdapProfiles().values()) {
                final String guidAttributeName = ldapProfile.readSettingAsString(PwmSetting.LDAP_GUID_ATTRIBUTE);
                if (!"DN".equalsIgnoreCase(guidAttributeName) && !"VENDORGUID".equalsIgnoreCase(guidAttributeName)) {
                    try {
                        // check if it is unique
                        UserSearchEngine.SearchConfiguration searchConfiguration = new UserSearchEngine.SearchConfiguration();
                        searchConfiguration.setFilter("(" + guidAttributeName + "=" + guidValue + ")");
                        UserSearchEngine userSearchEngine = new UserSearchEngine(pwmApplication, sessionLabel);
                        final UserIdentity result = userSearchEngine.performSingleUserSearch(searchConfiguration);
                        exists = result != null;
                    } catch (PwmOperationalException e) {
                        if (e.getError() != PwmError.ERROR_CANT_MATCH_USER) {
                            LOGGER.warn(sessionLabel, "error while searching to verify new unique GUID value: " + e.getError());
                        }
                    }
                }
            }
            return exists;
        }

        private static String assignGuidToUser(
                final PwmApplication pwmApplication,
                final SessionLabel sessionLabel,
                final UserIdentity userIdentity,
                final String guidAttributeName
        )
                throws ChaiUnavailableException, PwmUnrecoverableException
        {
            int attempts = 0;
            String newGuid = null;

            while (attempts < 10 && newGuid == null) {
                attempts++;
                newGuid = generateGuidValue();
                if (searchForExistingGuidValue(pwmApplication, sessionLabel, newGuid)) {
                    newGuid = null;
                }
            }

            if (newGuid == null) {
                throw new PwmUnrecoverableException(new ErrorInformation(PwmError.ERROR_UNKNOWN,"unable to generate unique GUID value for user " + userIdentity));
            }

            addConfiguredUserObjectClass(userIdentity, pwmApplication);
            try {
                // write it to the directory
                final ChaiUser chaiUser = pwmApplication.getProxiedChaiUser(userIdentity);
                chaiUser.writeStringAttribute(guidAttributeName, newGuid);
                LOGGER.info(sessionLabel, "added GUID value '" + newGuid + "' to user " + userIdentity);
                return newGuid;
            } catch (ChaiOperationException e) {
                final String errorMsg = "unable to write GUID value to user attribute " + guidAttributeName + " : " + e.getMessage() + ", cannot write GUID value to user " + userIdentity;
                final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_UNKNOWN, errorMsg);
                LOGGER.error(errorInformation.toDebugStr());
                throw new PwmUnrecoverableException(errorInformation);
            }
        }

        private static String generateGuidValue() {
            final StringBuilder sb = new StringBuilder();
            sb.append(Long.toHexString(System.currentTimeMillis()).toUpperCase());
            while (sb.length() < 12) {
                sb.insert(0, "0");
            }
            sb.insert(0, PwmRandom.getInstance().alphaNumericString(20).toUpperCase());
            return sb.toString();
        }
    }


    public static ChaiProvider createChaiProvider(
            final LdapProfile ldapProfile,
            final Configuration config,
            final String userDN,
            final String userPassword
    )
            throws ChaiUnavailableException
    {
        final List<String> ldapURLs = ldapProfile.readSettingAsStringArray(PwmSetting.LDAP_SERVER_URLS);
        final ChaiConfiguration chaiConfig = createChaiConfiguration(config, ldapProfile, ldapURLs, userDN,
                userPassword);
        LOGGER.trace("creating new chai provider using config of " + chaiConfig.toString());
        return ChaiProviderFactory.createProvider(chaiConfig);
    }

    public static ChaiProvider createChaiProvider(
            final Configuration config,
            final LdapProfile ldapProfile,
            final List<String> ldapURLs,
            final String userDN,
            final String userPassword
    )
            throws ChaiUnavailableException {
        final ChaiConfiguration chaiConfig = createChaiConfiguration( config, ldapProfile, ldapURLs, userDN, userPassword);
        LOGGER.trace("creating new chai provider using config of " + chaiConfig.toString());
        return ChaiProviderFactory.createProvider(chaiConfig);
    }

    public static ChaiConfiguration createChaiConfiguration(
            final Configuration config,
            final LdapProfile ldapProfile
    )
    {
        final List<String> ldapURLs = ldapProfile.readSettingAsStringArray(PwmSetting.LDAP_SERVER_URLS);
        final String userDN = ldapProfile.readSettingAsString(PwmSetting.LDAP_PROXY_USER_DN);
        final String userPassword = ldapProfile.readSettingAsString(PwmSetting.LDAP_PROXY_USER_PASSWORD);
        return createChaiConfiguration(config,ldapProfile,ldapURLs,userDN,userPassword);
    }

    public static ChaiConfiguration createChaiConfiguration(
            final Configuration config,
            final LdapProfile ldapProfile,
            final List<String> ldapURLs,
            final String userDN,
            final String userPassword
    )
    {

        final ChaiConfiguration chaiConfig = new ChaiConfiguration(ldapURLs, userDN, userPassword);

        chaiConfig.setSetting(ChaiSetting.PROMISCUOUS_SSL, config.readAppProperty(AppProperty.LDAP_PROMISCUOUS_ENABLE));
        chaiConfig.setSetting(ChaiSetting.EDIRECTORY_ENABLE_NMAS, Boolean.toString(config.readSettingAsBoolean(PwmSetting.EDIRECTORY_ENABLE_NMAS)));

        chaiConfig.setSetting(ChaiSetting.CR_CHAI_STORAGE_ATTRIBUTE, config.readSettingAsString(PwmSetting.CHALLENGE_USER_ATTRIBUTE));
        chaiConfig.setSetting(ChaiSetting.CR_ALLOW_DUPLICATE_RESPONSES, Boolean.toString(config.readSettingAsBoolean(PwmSetting.CHALLENGE_ALLOW_DUPLICATE_RESPONSES)));
        chaiConfig.setSetting(ChaiSetting.CR_CASE_INSENSITIVE, Boolean.toString(config.readSettingAsBoolean(PwmSetting.CHALLENGE_CASE_INSENSITIVE)));
        {
            final String setting = config.readAppProperty(AppProperty.SECURITY_RESPONSES_HASH_ITERATIONS);
            if (setting != null && setting.length() > 0) {
                final int intValue = Integer.parseInt(setting);
                chaiConfig.setSetting(ChaiSetting.CR_CHAI_SALT_COUNT, Integer.toString(intValue));
            }
        }

        chaiConfig.setSetting(ChaiSetting.JNDI_ENABLE_POOL, "false"); // can cause issues with previous password authentication

        chaiConfig.setSetting(ChaiSetting.CR_DEFAULT_FORMAT_TYPE, Answer.FormatType.SHA1_SALT.toString());
        final String storageMethodString = config.readSettingAsString(PwmSetting.CHALLENGE_STORAGE_HASHED);
        try {
            final Answer.FormatType formatType = Answer.FormatType.valueOf(storageMethodString);
            chaiConfig.setSetting(ChaiSetting.CR_DEFAULT_FORMAT_TYPE, formatType.toString());
        } catch (Exception e) {
            LOGGER.error("unknown CR storage format type '" + storageMethodString + "' ");
        }

        final X509Certificate[] ldapServerCerts = ldapProfile.readSettingAsCertificate(PwmSetting.LDAP_SERVER_CERTS);
        if (ldapServerCerts != null && ldapServerCerts.length > 0) {
            final X509TrustManager tm = new X509Utils.PwmTrustManager(ldapServerCerts);
            chaiConfig.setTrustManager(new X509TrustManager[]{tm});
        }

        final String idleTimeoutMsString = config.readAppProperty(AppProperty.LDAP_CONNECTION_TIMEOUT);
        chaiConfig.setSetting(ChaiSetting.LDAP_CONNECT_TIMEOUT,idleTimeoutMsString);

        // set the watchdog idle timeout.
        final int idleTimeoutMs = (int)config.readSettingAsLong(PwmSetting.LDAP_IDLE_TIMEOUT) * 1000;
        if (idleTimeoutMs > 0) {
            chaiConfig.setSetting(ChaiSetting.WATCHDOG_ENABLE, "true");
            chaiConfig.setSetting(ChaiSetting.WATCHDOG_IDLE_TIMEOUT, idleTimeoutMsString);
            chaiConfig.setSetting(ChaiSetting.WATCHDOG_CHECK_FREQUENCY, Long.toString(5 * 1000));
        } else {
            chaiConfig.setSetting(ChaiSetting.WATCHDOG_ENABLE, "false");
        }

        if (config.readSettingAsBoolean(PwmSetting.AD_ENFORCE_PW_HISTORY_ON_SET)) {
            chaiConfig.setSetting(ChaiSetting.AD_SET_POLICY_HINTS_ON_PW_SET,"true");
        }

        // enable caching
        //chaiConfig.setSetting(ChaiSetting.CACHE_ENABLE, "true");
        //chaiConfig.setSetting(ChaiSetting.CACHE_MAXIMUM_SIZE, "5000");

        // write out any configured values;
        final String rawValue = config.readAppProperty(AppProperty.LDAP_CHAI_SETTINGS);
        final String[] rawValues = rawValue != null ? rawValue.split(AppProperty.VALUE_SEPARATOR) : new String[0];
        final Map<String, String> configuredSettings = Configuration.convertStringListToNameValuePair(Arrays.asList(rawValues), "=");
        for (final String key : configuredSettings.keySet()) {
            if (key != null && !key.isEmpty()) {
                final ChaiSetting theSetting = ChaiSetting.forKey(key);
                if (theSetting == null) {
                    LOGGER.error("ignoring unknown chai setting '" + key + "'");
                } else {
                    chaiConfig.setSetting(theSetting, configuredSettings.get(key));
                }
            }
        }

        // set ldap referrals
        chaiConfig.setSetting(ChaiSetting.LDAP_FOLLOW_REFERRALS,String.valueOf(config.readSettingAsBoolean(PwmSetting.LDAP_FOLLOW_REFERRALS)));

        // enable wire trace;
        if (config.readSettingAsBoolean(PwmSetting.LDAP_ENABLE_WIRE_TRACE)) {
            chaiConfig.setSetting(ChaiSetting.WIRETRACE_ENABLE, "true");
        }

        return chaiConfig;
    }

    public static String readLdapUsernameValue(
            final PwmApplication pwmApplication,
            final UserIdentity userIdentity
    )
            throws ChaiUnavailableException, ChaiOperationException, PwmUnrecoverableException
    {
        final String profileID = userIdentity.getLdapProfileID();
        final String uIDattr = pwmApplication.getConfig().getLdapProfiles().get(profileID).getUsernameAttribute();
        final UserDataReader userDataReader = LdapUserDataReader.appProxiedReader(pwmApplication, userIdentity);
        return userDataReader.readStringAttribute(uIDattr);
    }
}
