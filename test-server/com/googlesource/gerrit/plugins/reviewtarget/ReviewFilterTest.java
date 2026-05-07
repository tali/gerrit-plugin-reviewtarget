package com.googlesource.gerrit.plugins.reviewtarget;

import org.junit.Test;

import static com.google.common.truth.Truth.assertThat;

public class ReviewFilterTest {
  @Test
  public void isPathToBeReviewed_1() {
    var reviewFilter = new ReviewFilter("src");
    assertThat(reviewFilter.isPathToBeReviewed("test", true))
        .isEqualTo(ReviewFilter.Selected.NO_MATCH);
    assertThat(reviewFilter.isPathToBeReviewed("src", true))
        .isEqualTo(ReviewFilter.Selected.POSITIVE);
    assertThat(reviewFilter.isPathToBeReviewed("component/src", true))
        .isEqualTo(ReviewFilter.Selected.POSITIVE);
    assertThat(reviewFilter.isPathToBeReviewed("component/test", true))
        .isEqualTo(ReviewFilter.Selected.NO_MATCH);
    assertThat(reviewFilter.isPathToBeReviewed("src", false))
        .isEqualTo(ReviewFilter.Selected.POSITIVE);
    assertThat(reviewFilter.isPathToBeReviewed("src1", false))
        .isEqualTo(ReviewFilter.Selected.NO_MATCH);
    assertThat(reviewFilter.isPathToBeReviewed("file.src", false))
        .isEqualTo(ReviewFilter.Selected.NO_MATCH);
  }

  @Test
  public void matchAll_emptyFilter() {
    assertThat(new ReviewFilter("").matchAll()).isTrue();
    assertThat(new ReviewFilter(java.util.List.of()).matchAll()).isTrue();
  }

  @Test
  public void matchAll_nonEmptyFilter() {
    assertThat(new ReviewFilter("src").matchAll()).isFalse();
    assertThat(new ReviewFilter(java.util.List.of("src")).matchAll()).isFalse();
  }

  @Test
  public void isPathToBeReviewed_2() {
    var reviewFilter = new ReviewFilter("a.*\n!*.b");
    assertThat(reviewFilter.isPathToBeReviewed("x.x", false))
        .isEqualTo(ReviewFilter.Selected.NO_MATCH);
    assertThat(reviewFilter.isPathToBeReviewed("a.x", false))
        .isEqualTo(ReviewFilter.Selected.POSITIVE);
    assertThat(reviewFilter.isPathToBeReviewed("a.b", false))
        .isEqualTo(ReviewFilter.Selected.NEGATIVE);
    assertThat(reviewFilter.isPathToBeReviewed("x.b", false))
        .isEqualTo(ReviewFilter.Selected.NEGATIVE);
  }
}
