/*
 * Sonar, open source software quality management tool.
 * Copyright (C) 2010-2011 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * Sonar is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * Sonar is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Sonar; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonar.ide.eclipse.internal.mylyn.core;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.mylyn.commons.net.AuthenticationCredentials;
import org.eclipse.mylyn.commons.net.AuthenticationType;
import org.eclipse.mylyn.tasks.core.TaskRepository;
import org.sonar.wsclient.Host;
import org.sonar.wsclient.Sonar;
import org.sonar.wsclient.WSClientFactory;
import org.sonar.wsclient.services.Review;
import org.sonar.wsclient.services.ReviewQuery;
import org.sonar.wsclient.services.ServerQuery;

import java.util.ArrayList;
import java.util.List;

public class SonarClient {

  private static final String TYPE_FALSE_POSITIVE = "FALSE_POSITIVE"; //$NON-NLS-1$

  private static final String STATUS_OPEN = "OPEN"; //$NON-NLS-1$

  private TaskRepository repository;

  public SonarClient(TaskRepository repository) {
    this.repository = repository;
  }

  public Review getReview(long id, IProgressMonitor monitor) {
    Sonar sonar = create();
    Review review = sonar.find(new ReviewQuery().setId(id));
    if (review == null) {
      // Workaround for http://jira.codehaus.org/browse/SONAR-2421
      review = sonar.find(new ReviewQuery().setId(id).setReviewType(TYPE_FALSE_POSITIVE));
    }
    return review;
  }

  public List<Review> getReviews(IProgressMonitor monitor) {
    List<Review> result = new ArrayList<Review>();
    Sonar sonar = create();
    String assignee = repository.getCredentials(AuthenticationType.REPOSITORY).getUserName();

    ReviewQuery query = new ReviewQuery()
        .setStatuses(STATUS_OPEN)
        .setAssigneeLoginsOrIds(assignee);
    result.addAll(sonar.findAll(query));

    // Workaround for http://jira.codehaus.org/browse/SONAR-2421
    query.setReviewType(TYPE_FALSE_POSITIVE);
    result.addAll(sonar.findAll(query));

    return result;
  }

  public String getServerVersion() {
    return create().find(new ServerQuery()).getVersion();
  }

  private Sonar create() {
    Host host = new Host(repository.getRepositoryUrl());
    AuthenticationCredentials credentials = repository.getCredentials(AuthenticationType.REPOSITORY);
    if (credentials != null) {
      host.setUsername(credentials.getUserName());
      host.setPassword(credentials.getPassword());
    }
    return WSClientFactory.create(host);
  }
}
