/**
 * ContainerProxy
 *
 * Copyright (C) 2016-2018 Open Analytics
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.containerproxy.auth.impl;

import java.io.ByteArrayOutputStream;
import java.util.Collections;

import javax.inject.Inject;

import org.apache.kerby.kerberos.kerb.type.ap.ApReq;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.springframework.core.env.Environment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.kerberos.authentication.KerberosAuthenticationProvider;
import org.springframework.security.kerberos.authentication.KerberosServiceAuthenticationProvider;
import org.springframework.security.kerberos.authentication.KerberosServiceRequestToken;
import org.springframework.security.kerberos.authentication.sun.SunJaasKerberosClient;
import org.springframework.security.kerberos.authentication.sun.SunJaasKerberosTicketValidator;
import org.springframework.security.kerberos.web.authentication.SpnegoAuthenticationProcessingFilter;
import org.springframework.security.kerberos.web.authentication.SpnegoEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import eu.openanalytics.containerproxy.auth.IAuthenticationBackend;

public class KerberosAuthenticationBackend implements IAuthenticationBackend {

	public static final String NAME = "kerberos";

	@Inject
	Environment environment;
	
	@Inject
	AuthenticationManager authenticationManager;

	@Override
	public String getName() {
		return NAME;
	}

	@Override
	public boolean hasAuthorization() {
		return true;
	}

	@Override
	public void configureHttpSecurity(HttpSecurity http) throws Exception {

		SpnegoAuthenticationProcessingFilter filter = new SpnegoAuthenticationProcessingFilter();
		filter.setAuthenticationManager(authenticationManager);

		http
			.exceptionHandling().authenticationEntryPoint(new SpnegoEntryPoint("/login")).and()
			.addFilterBefore(filter, BasicAuthenticationFilter.class);
	}

	@Override
	public void configureAuthenticationManagerBuilder(AuthenticationManagerBuilder auth) throws Exception {
		UserDetailsService uds = new DummyUserDetailsService();

		KerberosAuthenticationProvider provider = new KerberosAuthenticationProvider();
		SunJaasKerberosClient client = new SunJaasKerberosClient();
		client.setDebug(true);
		provider.setKerberosClient(client);
		provider.setUserDetailsService(uds);
		auth.authenticationProvider(provider);

		KerberosServiceAuthenticationProvider spnegoProvider = new KerberosServiceAuthenticationProvider() {
			@Override
			public Authentication authenticate(Authentication authentication) throws AuthenticationException {
				KerberosServiceRequestToken auth = (KerberosServiceRequestToken) super.authenticate(authentication);
				
				// Test: check for delegated credentials
				try {
					GSSContext context = auth.getTicketValidation().getGssContext();
					if (context.getCredDelegState()) {
						GSSCredential cred = context.getDelegCred();
						System.out.println("Delegated credentials found:");
						System.out.println(String.valueOf(cred));
					} else {
						System.out.println("No delegated credentials available!");
					}
				} catch (Exception e) {
					throw new BadCredentialsException("Failed to obtain delegated credentials", e);
				}
				
				// Test: parse token ticket
				try {
					byte[] spnegoToken = auth.getToken();
					byte[] apReqHeader = {(byte) 0x1, (byte) 0};
					
					int offset = 0;
					while (offset < spnegoToken.length - 1) {
						if (spnegoToken[offset] == apReqHeader[0] && spnegoToken[offset + 1] == apReqHeader[1]) {
							offset += 2;
							break;
						}
					}
					ByteArrayOutputStream tokenMinusHeader = new ByteArrayOutputStream();
					tokenMinusHeader.write(spnegoToken, offset, spnegoToken.length - offset);
					System.out.println("Stripped " + offset + " header bytes from token.");
					
					ApReq apReq = new ApReq();
					apReq.decode(tokenMinusHeader.toByteArray());
					System.out.println("Parsed ticket " + apReq.getTicket().getSname());
					System.out.println("Ticket class: " + apReq.getTicket().getClass().getName());
				} catch (Exception e) {
					throw new BadCredentialsException("Failed to parse AP_REQ ticket", e);
				}
			
				return auth;
			}
		};
		SunJaasKerberosTicketValidator ticketValidator = new SunJaasKerberosTicketValidator();
		ticketValidator.setServicePrincipal(environment.getProperty("proxy.kerberos.service-principal"));
		ticketValidator.setKeyTabLocation(new FileSystemResource(environment.getProperty("proxy.kerberos.service-keytab")));
		ticketValidator.setDebug(true);
		ticketValidator.setHoldOnToGSSContext(true);
		ticketValidator.afterPropertiesSet();
		
		spnegoProvider.setTicketValidator(ticketValidator);
		spnegoProvider.setUserDetailsService(uds);
		auth.authenticationProvider(spnegoProvider);
	}

	private static class DummyUserDetailsService implements UserDetailsService {
		@Override
		public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
			return new User(username, "", Collections.emptyList());
		}
	}

}