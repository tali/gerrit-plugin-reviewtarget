// Copyright (C) 2022 Siemens Mobility GmbH
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.reviewtarget;

import com.google.gerrit.extensions.annotations.Exports;
import com.google.gerrit.index.query.PostFilterPredicate;
import com.google.gerrit.index.query.Predicate;
import com.google.gerrit.index.query.QueryParseException;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gerrit.server.query.change.ChangeQueryBuilder;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import static java.util.Objects.requireNonNull;

@Singleton
public class HasReviewTargetOperand implements ChangeQueryBuilder.ChangeHasOperandFactory {

  private final MatchReviewTarget matchReviewTarget;
  final static String OPERAND = "selected";

  public static class Module extends AbstractModule {
    @Override
    protected void configure() {
      bind(ChangeQueryBuilder.ChangeHasOperandFactory.class)
          .annotatedWith(Exports.named(OPERAND))
          .to(HasReviewTargetOperand.class);
    }
  }

  @Inject
  HasReviewTargetOperand(MatchReviewTarget matchReviewTarget) {
    this.matchReviewTarget = requireNonNull(matchReviewTarget);
  }

  @Override
  public Predicate<ChangeData> create(ChangeQueryBuilder builder)
      throws QueryParseException {
    return new HasReviewTargetPredicate();
  }

  private class HasReviewTargetPredicate extends PostFilterPredicate<ChangeData> {

    HasReviewTargetPredicate() {
      super("has", OPERAND);
    }

    @Override
    public boolean match(ChangeData object) {
      return matchReviewTarget.checkReviewTarget(object.change());
    }

    @Override
    public int getCost() {
      return 1;
    }
  }
}
