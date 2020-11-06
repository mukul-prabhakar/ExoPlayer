/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.ext.ima;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.ads.interactivemedia.v3.api.Ad;
import com.google.ads.interactivemedia.v3.api.AdDisplayContainer;
import com.google.ads.interactivemedia.v3.api.AdEvent;
import com.google.ads.interactivemedia.v3.api.AdEvent.AdEventType;
import com.google.ads.interactivemedia.v3.api.AdPodInfo;
import com.google.ads.interactivemedia.v3.api.AdsManager;
import com.google.ads.interactivemedia.v3.api.AdsManagerLoadedEvent;
import com.google.ads.interactivemedia.v3.api.AdsRenderingSettings;
import com.google.ads.interactivemedia.v3.api.AdsRequest;
import com.google.ads.interactivemedia.v3.api.FriendlyObstruction;
import com.google.ads.interactivemedia.v3.api.ImaSdkSettings;
import com.google.ads.interactivemedia.v3.api.player.AdMediaInfo;
import com.google.ads.interactivemedia.v3.api.player.ContentProgressProvider;
import com.google.ads.interactivemedia.v3.api.player.VideoAdPlayer;
import com.google.ads.interactivemedia.v3.api.player.VideoProgressUpdate;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.Timeline.Period;
import com.google.android.exoplayer2.ext.ima.ImaUtil.ImaFactory;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.MaskingMediaSource.PlaceholderTimeline;
import com.google.android.exoplayer2.source.ads.AdPlaybackState;
import com.google.android.exoplayer2.source.ads.AdsLoader;
import com.google.android.exoplayer2.source.ads.AdsMediaSource;
import com.google.android.exoplayer2.source.ads.AdsMediaSource.AdLoadException;
import com.google.android.exoplayer2.source.ads.SinglePeriodAdTimeline;
import com.google.android.exoplayer2.testutil.FakeMediaSource;
import com.google.android.exoplayer2.testutil.FakeTimeline;
import com.google.android.exoplayer2.testutil.FakeTimeline.TimelineWindowDefinition;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;
import org.robolectric.shadows.ShadowSystemClock;

/** Tests for {@link ImaAdsLoader}. */
@RunWith(AndroidJUnit4.class)
public final class ImaAdsLoaderTest {

  private static final long CONTENT_DURATION_US = 10 * C.MICROS_PER_SECOND;
  private static final Timeline CONTENT_TIMELINE =
      new FakeTimeline(
          new TimelineWindowDefinition(
              /* isSeekable= */ true, /* isDynamic= */ false, CONTENT_DURATION_US));
  private static final long CONTENT_PERIOD_DURATION_US =
      CONTENT_TIMELINE.getPeriod(/* periodIndex= */ 0, new Period()).durationUs;
  private static final Uri TEST_URI = Uri.parse("https://www.google.com");
  private static final DataSpec TEST_DATA_SPEC = new DataSpec(TEST_URI);
  private static final Object TEST_ADS_ID = new Object();
  private static final AdMediaInfo TEST_AD_MEDIA_INFO = new AdMediaInfo("https://www.google.com");
  private static final long TEST_AD_DURATION_US = 5 * C.MICROS_PER_SECOND;
  private static final ImmutableList<Float> PREROLL_CUE_POINTS_SECONDS = ImmutableList.of(0f);

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private ImaSdkSettings mockImaSdkSettings;
  @Mock private AdsRenderingSettings mockAdsRenderingSettings;
  @Mock private AdDisplayContainer mockAdDisplayContainer;
  @Mock private AdsManager mockAdsManager;
  @Mock private AdsRequest mockAdsRequest;
  @Mock private AdsManagerLoadedEvent mockAdsManagerLoadedEvent;
  @Mock private com.google.ads.interactivemedia.v3.api.AdsLoader mockAdsLoader;
  @Mock private FriendlyObstruction mockFriendlyObstruction;
  @Mock private ImaFactory mockImaFactory;
  @Mock private AdPodInfo mockAdPodInfo;
  @Mock private Ad mockPrerollSingleAd;

  private AdsMediaSource adsMediaSource;
  private ViewGroup adViewGroup;
  private AdsLoader.AdViewProvider adViewProvider;
  private AdsLoader.AdViewProvider audioAdsAdViewProvider;
  private AdEvent.AdEventListener adEventListener;
  private ContentProgressProvider contentProgressProvider;
  private VideoAdPlayer videoAdPlayer;
  private TestAdsLoaderListener adsLoaderListener;
  private FakePlayer fakeExoPlayer;
  private ImaAdsLoader imaAdsLoader;

  @Before
  public void setUp() {
    setupMocks();
    adViewGroup = new FrameLayout(getApplicationContext());
    View adOverlayView = new View(getApplicationContext());
    adViewProvider =
        new AdsLoader.AdViewProvider() {
          @Override
          public ViewGroup getAdViewGroup() {
            return adViewGroup;
          }

          @Override
          public ImmutableList<AdsLoader.OverlayInfo> getAdOverlayInfos() {
            return ImmutableList.of(
                new AdsLoader.OverlayInfo(adOverlayView, AdsLoader.OverlayInfo.PURPOSE_CLOSE_AD));
          }
        };
    audioAdsAdViewProvider =
        new AdsLoader.AdViewProvider() {
          @Override
          public ViewGroup getAdViewGroup() {
            return null;
          }

          @Override
          public ImmutableList<AdsLoader.OverlayInfo> getAdOverlayInfos() {
            return ImmutableList.of();
          }
        };
  }

  @After
  public void teardown() {
    if (imaAdsLoader != null) {
      imaAdsLoader.release();
    }
  }

  @Test
  public void builder_overridesPlayerType() {
    when(mockImaSdkSettings.getPlayerType()).thenReturn("test player type");
    setupPlayback(CONTENT_TIMELINE, PREROLL_CUE_POINTS_SECONDS);
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);

    verify(mockImaSdkSettings).setPlayerType("google/exo.ext.ima");
  }

  @Test
  public void start_setsAdUiViewGroup() {
    setupPlayback(CONTENT_TIMELINE, PREROLL_CUE_POINTS_SECONDS);
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);

    verify(mockImaFactory, atLeastOnce()).createAdDisplayContainer(adViewGroup, videoAdPlayer);
    verify(mockImaFactory, never()).createAudioAdDisplayContainer(any(), any());
    verify(mockAdDisplayContainer).registerFriendlyObstruction(mockFriendlyObstruction);
  }

  @Test
  public void startForAudioOnlyAds_createsAudioOnlyAdDisplayContainer() {
    setupPlayback(CONTENT_TIMELINE, PREROLL_CUE_POINTS_SECONDS);
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, audioAdsAdViewProvider, adsLoaderListener);

    verify(mockImaFactory, atLeastOnce())
        .createAudioAdDisplayContainer(getApplicationContext(), videoAdPlayer);
    verify(mockImaFactory, never()).createAdDisplayContainer(any(), any());
    verify(mockAdDisplayContainer, never()).registerFriendlyObstruction(any());
  }

  @Test
  public void start_withPlaceholderContent_initializedAdsLoader() {
    Timeline placeholderTimeline = new PlaceholderTimeline(MediaItem.fromUri(Uri.EMPTY));
    setupPlayback(placeholderTimeline, PREROLL_CUE_POINTS_SECONDS);
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);

    // We'll only create the rendering settings when initializing the ads loader.
    verify(mockImaFactory).createAdsRenderingSettings();
  }

  @Test
  public void start_updatesAdPlaybackState() {
    setupPlayback(CONTENT_TIMELINE, PREROLL_CUE_POINTS_SECONDS);
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);

    assertThat(adsLoaderListener.adPlaybackState)
        .isEqualTo(
            new AdPlaybackState(/* adGroupTimesUs...= */ 0)
                .withContentDurationUs(CONTENT_PERIOD_DURATION_US));
  }

  @Test
  public void startAfterRelease() {
    setupPlayback(CONTENT_TIMELINE, PREROLL_CUE_POINTS_SECONDS);
    imaAdsLoader.release();
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);
  }

  @Test
  public void startAndCallbacksAfterRelease() {
    setupPlayback(CONTENT_TIMELINE, PREROLL_CUE_POINTS_SECONDS);
    // Request ads in order to get a reference to the ad event listener.
    imaAdsLoader.requestAds(TEST_DATA_SPEC, adViewGroup);
    imaAdsLoader.release();
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);
    fakeExoPlayer.setPlayingContentPosition(/* position= */ 0);
    fakeExoPlayer.setState(Player.STATE_READY, true);

    // If callbacks are invoked there is no crash.
    // Note: we can't currently call getContentProgress/getAdProgress as a VerifyError is thrown
    // when using Robolectric and accessing VideoProgressUpdate.VIDEO_TIME_NOT_READY, due to the IMA
    // SDK being proguarded.
    imaAdsLoader.requestAds(TEST_DATA_SPEC, adViewGroup);
    adEventListener.onAdEvent(getAdEvent(AdEventType.LOADED, mockPrerollSingleAd));
    videoAdPlayer.loadAd(TEST_AD_MEDIA_INFO, mockAdPodInfo);
    adEventListener.onAdEvent(getAdEvent(AdEventType.CONTENT_PAUSE_REQUESTED, mockPrerollSingleAd));
    videoAdPlayer.playAd(TEST_AD_MEDIA_INFO);
    adEventListener.onAdEvent(getAdEvent(AdEventType.STARTED, mockPrerollSingleAd));
    videoAdPlayer.pauseAd(TEST_AD_MEDIA_INFO);
    videoAdPlayer.stopAd(TEST_AD_MEDIA_INFO);
    imaAdsLoader.onPlayerError(ExoPlaybackException.createForSource(new IOException()));
    imaAdsLoader.onPositionDiscontinuity(Player.DISCONTINUITY_REASON_SEEK);
    adEventListener.onAdEvent(getAdEvent(AdEventType.CONTENT_RESUME_REQUESTED, /* ad= */ null));
    imaAdsLoader.handlePrepareError(
        adsMediaSource, /* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, new IOException());
  }

  @Test
  public void playback_withPrerollAd_marksAdAsPlayed() {
    setupPlayback(CONTENT_TIMELINE, PREROLL_CUE_POINTS_SECONDS);

    // Load the preroll ad.
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);
    adEventListener.onAdEvent(getAdEvent(AdEventType.LOADED, mockPrerollSingleAd));
    videoAdPlayer.loadAd(TEST_AD_MEDIA_INFO, mockAdPodInfo);
    adEventListener.onAdEvent(getAdEvent(AdEventType.CONTENT_PAUSE_REQUESTED, mockPrerollSingleAd));

    // Play the preroll ad.
    videoAdPlayer.playAd(TEST_AD_MEDIA_INFO);
    fakeExoPlayer.setPlayingAdPosition(
        /* adGroupIndex= */ 0,
        /* adIndexInAdGroup= */ 0,
        /* position= */ 0,
        /* contentPosition= */ 0);
    fakeExoPlayer.setState(Player.STATE_READY, true);
    adEventListener.onAdEvent(getAdEvent(AdEventType.STARTED, mockPrerollSingleAd));
    adEventListener.onAdEvent(getAdEvent(AdEventType.FIRST_QUARTILE, mockPrerollSingleAd));
    adEventListener.onAdEvent(getAdEvent(AdEventType.MIDPOINT, mockPrerollSingleAd));
    adEventListener.onAdEvent(getAdEvent(AdEventType.THIRD_QUARTILE, mockPrerollSingleAd));

    // Play the content.
    fakeExoPlayer.setPlayingContentPosition(0);
    videoAdPlayer.stopAd(TEST_AD_MEDIA_INFO);
    adEventListener.onAdEvent(getAdEvent(AdEventType.CONTENT_RESUME_REQUESTED, /* ad= */ null));

    // Verify that the preroll ad has been marked as played.
    assertThat(adsLoaderListener.adPlaybackState)
        .isEqualTo(
            new AdPlaybackState(/* adGroupTimesUs...= */ 0)
                .withContentDurationUs(CONTENT_PERIOD_DURATION_US)
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                .withAdUri(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, TEST_URI)
                .withAdDurationsUs(new long[][] {{TEST_AD_DURATION_US}})
                .withPlayedAd(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0)
                .withAdResumePositionUs(/* adResumePositionUs= */ 0));
  }

  @Test
  public void playback_withMidrollFetchError_marksAdAsInErrorState() {
    AdEvent mockMidrollFetchErrorAdEvent = mock(AdEvent.class);
    when(mockMidrollFetchErrorAdEvent.getType()).thenReturn(AdEventType.AD_BREAK_FETCH_ERROR);
    when(mockMidrollFetchErrorAdEvent.getAdData())
        .thenReturn(ImmutableMap.of("adBreakTime", "20.5"));
    setupPlayback(CONTENT_TIMELINE, ImmutableList.of(20.5f));

    // Simulate loading an empty midroll ad.
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);
    adEventListener.onAdEvent(mockMidrollFetchErrorAdEvent);

    assertThat(adsLoaderListener.adPlaybackState)
        .isEqualTo(
            new AdPlaybackState(/* adGroupTimesUs...= */ 20_500_000)
                .withContentDurationUs(CONTENT_PERIOD_DURATION_US)
                .withAdDurationsUs(new long[][] {{TEST_AD_DURATION_US}})
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                .withAdLoadError(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));
  }

  @Test
  public void playback_withMidrollFetchError_updatesContentProgress() {
    AdEvent mockMidrollFetchErrorAdEvent = mock(AdEvent.class);
    when(mockMidrollFetchErrorAdEvent.getType()).thenReturn(AdEventType.AD_BREAK_FETCH_ERROR);
    when(mockMidrollFetchErrorAdEvent.getAdData())
        .thenReturn(ImmutableMap.of("adBreakTime", "5.5"));
    setupPlayback(CONTENT_TIMELINE, ImmutableList.of(5.5f));

    // Simulate loading an empty midroll ad and advancing the player position.
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);
    adEventListener.onAdEvent(mockMidrollFetchErrorAdEvent);
    long playerPositionUs = CONTENT_DURATION_US - C.MICROS_PER_SECOND;
    long playerPositionInPeriodUs =
        playerPositionUs + TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    long periodDurationUs =
        CONTENT_TIMELINE.getPeriod(/* periodIndex= */ 0, new Period()).durationUs;
    fakeExoPlayer.setPlayingContentPosition(C.usToMs(playerPositionUs));

    // Verify the content progress is updated to reflect the new player position.
    assertThat(contentProgressProvider.getContentProgress())
        .isEqualTo(
            new VideoProgressUpdate(
                C.usToMs(playerPositionInPeriodUs), C.usToMs(periodDurationUs)));
  }

  @Test
  public void playback_withPostrollFetchError_marksAdAsInErrorState() {
    AdEvent mockPostrollFetchErrorAdEvent = mock(AdEvent.class);
    when(mockPostrollFetchErrorAdEvent.getType()).thenReturn(AdEventType.AD_BREAK_FETCH_ERROR);
    when(mockPostrollFetchErrorAdEvent.getAdData())
        .thenReturn(ImmutableMap.of("adBreakTime", "-1"));
    setupPlayback(CONTENT_TIMELINE, ImmutableList.of(-1f));

    // Simulate loading an empty postroll ad.
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);
    adEventListener.onAdEvent(mockPostrollFetchErrorAdEvent);

    assertThat(adsLoaderListener.adPlaybackState)
        .isEqualTo(
            new AdPlaybackState(/* adGroupTimesUs...= */ C.TIME_END_OF_SOURCE)
                .withContentDurationUs(CONTENT_PERIOD_DURATION_US)
                .withAdDurationsUs(new long[][] {{TEST_AD_DURATION_US}})
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                .withAdLoadError(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));
  }

  @Test
  public void playback_withAdNotPreloadingBeforeTimeout_hasNoError() {
    // Simulate an ad at 2 seconds.
    long adGroupPositionInWindowUs = 2 * C.MICROS_PER_SECOND;
    long adGroupTimeUs =
        adGroupPositionInWindowUs
            + TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    ImmutableList<Float> cuePoints = ImmutableList.of((float) adGroupTimeUs / C.MICROS_PER_SECOND);
    setupPlayback(CONTENT_TIMELINE, cuePoints);

    // Advance playback to just before the midroll and simulate buffering.
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);
    fakeExoPlayer.setPlayingContentPosition(C.usToMs(adGroupPositionInWindowUs));
    fakeExoPlayer.setState(Player.STATE_BUFFERING, /* playWhenReady= */ true);
    // Advance before the timeout and simulating polling content progress.
    ShadowSystemClock.advanceBy(Duration.ofSeconds(1));
    contentProgressProvider.getContentProgress();

    assertThat(adsLoaderListener.adPlaybackState)
        .isEqualTo(
            ImaUtil.getInitialAdPlaybackStateForCuePoints(cuePoints)
                .withContentDurationUs(CONTENT_PERIOD_DURATION_US));
  }

  @Test
  public void playback_withAdNotPreloadingAfterTimeout_hasErrorAdGroup() {
    // Simulate an ad at 2 seconds.
    long adGroupPositionInWindowUs = 2 * C.MICROS_PER_SECOND;
    long adGroupTimeUs =
        adGroupPositionInWindowUs
            + TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    ImmutableList<Float> cuePoints = ImmutableList.of((float) adGroupTimeUs / C.MICROS_PER_SECOND);
    setupPlayback(CONTENT_TIMELINE, cuePoints);

    // Advance playback to just before the midroll and simulate buffering.
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);
    fakeExoPlayer.setPlayingContentPosition(C.usToMs(adGroupPositionInWindowUs));
    fakeExoPlayer.setState(Player.STATE_BUFFERING, /* playWhenReady= */ true);
    // Advance past the timeout and simulate polling content progress.
    ShadowSystemClock.advanceBy(Duration.ofSeconds(5));
    contentProgressProvider.getContentProgress();

    assertThat(adsLoaderListener.adPlaybackState)
        .isEqualTo(
            ImaUtil.getInitialAdPlaybackStateForCuePoints(cuePoints)
                .withContentDurationUs(CONTENT_PERIOD_DURATION_US)
                .withAdDurationsUs(new long[][] {{TEST_AD_DURATION_US}})
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                .withAdLoadError(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0));
  }

  @Test
  public void resumePlaybackBeforeMidroll_playsPreroll() {
    long midrollWindowTimeUs = 2 * C.MICROS_PER_SECOND;
    long midrollPeriodTimeUs =
        midrollWindowTimeUs + TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    ImmutableList<Float> cuePoints =
        ImmutableList.of(0f, (float) midrollPeriodTimeUs / C.MICROS_PER_SECOND);
    setupPlayback(CONTENT_TIMELINE, cuePoints);

    fakeExoPlayer.setPlayingContentPosition(C.usToMs(midrollWindowTimeUs) - 1_000);
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);

    verify(mockAdsRenderingSettings, never()).setPlayAdsAfterTime(anyDouble());
    assertThat(adsLoaderListener.adPlaybackState)
        .isEqualTo(
            ImaUtil.getInitialAdPlaybackStateForCuePoints(cuePoints)
                .withContentDurationUs(CONTENT_PERIOD_DURATION_US));
  }

  @Test
  public void resumePlaybackAtMidroll_skipsPreroll() {
    long midrollWindowTimeUs = 2 * C.MICROS_PER_SECOND;
    long midrollPeriodTimeUs =
        midrollWindowTimeUs + TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    ImmutableList<Float> cuePoints =
        ImmutableList.of(0f, (float) midrollPeriodTimeUs / C.MICROS_PER_SECOND);
    setupPlayback(CONTENT_TIMELINE, cuePoints);

    fakeExoPlayer.setPlayingContentPosition(C.usToMs(midrollWindowTimeUs));
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);

    ArgumentCaptor<Double> playAdsAfterTimeCaptor = ArgumentCaptor.forClass(Double.class);
    verify(mockAdsRenderingSettings).setPlayAdsAfterTime(playAdsAfterTimeCaptor.capture());
    double expectedPlayAdsAfterTimeUs = midrollPeriodTimeUs / 2d;
    assertThat(playAdsAfterTimeCaptor.getValue())
        .isWithin(0.1)
        .of(expectedPlayAdsAfterTimeUs / C.MICROS_PER_SECOND);
    assertThat(adsLoaderListener.adPlaybackState)
        .isEqualTo(
            ImaUtil.getInitialAdPlaybackStateForCuePoints(cuePoints)
                .withContentDurationUs(CONTENT_PERIOD_DURATION_US)
                .withSkippedAdGroup(/* adGroupIndex= */ 0));
  }

  @Test
  public void resumePlaybackAfterMidroll_skipsPreroll() {
    long midrollWindowTimeUs = 2 * C.MICROS_PER_SECOND;
    long midrollPeriodTimeUs =
        midrollWindowTimeUs + TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    ImmutableList<Float> cuePoints =
        ImmutableList.of(0f, (float) midrollPeriodTimeUs / C.MICROS_PER_SECOND);
    setupPlayback(CONTENT_TIMELINE, cuePoints);

    fakeExoPlayer.setPlayingContentPosition(C.usToMs(midrollWindowTimeUs) + 1_000);
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);

    ArgumentCaptor<Double> playAdsAfterTimeCaptor = ArgumentCaptor.forClass(Double.class);
    verify(mockAdsRenderingSettings).setPlayAdsAfterTime(playAdsAfterTimeCaptor.capture());
    double expectedPlayAdsAfterTimeUs = midrollPeriodTimeUs / 2d;
    assertThat(playAdsAfterTimeCaptor.getValue())
        .isWithin(0.1)
        .of(expectedPlayAdsAfterTimeUs / C.MICROS_PER_SECOND);
    assertThat(adsLoaderListener.adPlaybackState)
        .isEqualTo(
            ImaUtil.getInitialAdPlaybackStateForCuePoints(cuePoints)
                .withContentDurationUs(CONTENT_PERIOD_DURATION_US)
                .withSkippedAdGroup(/* adGroupIndex= */ 0));
  }

  @Test
  public void resumePlaybackBeforeSecondMidroll_playsFirstMidroll() {
    long firstMidrollWindowTimeUs = 2 * C.MICROS_PER_SECOND;
    long firstMidrollPeriodTimeUs =
        firstMidrollWindowTimeUs
            + TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    long secondMidrollWindowTimeUs = 4 * C.MICROS_PER_SECOND;
    long secondMidrollPeriodTimeUs =
        secondMidrollWindowTimeUs
            + TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    ImmutableList<Float> cuePoints =
        ImmutableList.of(
            (float) firstMidrollPeriodTimeUs / C.MICROS_PER_SECOND,
            (float) secondMidrollPeriodTimeUs / C.MICROS_PER_SECOND);
    setupPlayback(CONTENT_TIMELINE, cuePoints);

    fakeExoPlayer.setPlayingContentPosition(C.usToMs(secondMidrollWindowTimeUs) - 1_000);
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);

    verify(mockAdsRenderingSettings, never()).setPlayAdsAfterTime(anyDouble());
    assertThat(adsLoaderListener.adPlaybackState)
        .isEqualTo(
            ImaUtil.getInitialAdPlaybackStateForCuePoints(cuePoints)
                .withContentDurationUs(CONTENT_PERIOD_DURATION_US));
  }

  @Test
  public void resumePlaybackAtSecondMidroll_skipsFirstMidroll() {
    long firstMidrollWindowTimeUs = 2 * C.MICROS_PER_SECOND;
    long firstMidrollPeriodTimeUs =
        firstMidrollWindowTimeUs
            + TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    long secondMidrollWindowTimeUs = 4 * C.MICROS_PER_SECOND;
    long secondMidrollPeriodTimeUs =
        secondMidrollWindowTimeUs
            + TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    ImmutableList<Float> cuePoints =
        ImmutableList.of(
            (float) firstMidrollPeriodTimeUs / C.MICROS_PER_SECOND,
            (float) secondMidrollPeriodTimeUs / C.MICROS_PER_SECOND);
    setupPlayback(CONTENT_TIMELINE, cuePoints);

    fakeExoPlayer.setPlayingContentPosition(C.usToMs(secondMidrollWindowTimeUs));
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);

    ArgumentCaptor<Double> playAdsAfterTimeCaptor = ArgumentCaptor.forClass(Double.class);
    verify(mockAdsRenderingSettings).setPlayAdsAfterTime(playAdsAfterTimeCaptor.capture());
    double expectedPlayAdsAfterTimeUs = (firstMidrollPeriodTimeUs + secondMidrollPeriodTimeUs) / 2d;
    assertThat(playAdsAfterTimeCaptor.getValue())
        .isWithin(0.1)
        .of(expectedPlayAdsAfterTimeUs / C.MICROS_PER_SECOND);
    assertThat(adsLoaderListener.adPlaybackState)
        .isEqualTo(
            ImaUtil.getInitialAdPlaybackStateForCuePoints(cuePoints)
                .withContentDurationUs(CONTENT_PERIOD_DURATION_US)
                .withSkippedAdGroup(/* adGroupIndex= */ 0));
  }

  @Test
  public void resumePlaybackBeforeMidroll_withoutPlayAdBeforeStartPosition_skipsPreroll() {
    long midrollWindowTimeUs = 2 * C.MICROS_PER_SECOND;
    long midrollPeriodTimeUs =
        midrollWindowTimeUs + TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    ImmutableList<Float> cuePoints =
        ImmutableList.of(0f, (float) midrollPeriodTimeUs / C.MICROS_PER_SECOND);
    setupPlayback(
        CONTENT_TIMELINE,
        cuePoints,
        new ImaAdsLoader.Builder(getApplicationContext())
            .setPlayAdBeforeStartPosition(false)
            .setImaFactory(mockImaFactory)
            .setImaSdkSettings(mockImaSdkSettings)
            .build(),
        TEST_DATA_SPEC,
        TEST_ADS_ID);

    fakeExoPlayer.setPlayingContentPosition(C.usToMs(midrollWindowTimeUs) - 1_000);
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);

    ArgumentCaptor<Double> playAdsAfterTimeCaptor = ArgumentCaptor.forClass(Double.class);
    verify(mockAdsRenderingSettings).setPlayAdsAfterTime(playAdsAfterTimeCaptor.capture());
    double expectedPlayAdsAfterTimeUs = midrollPeriodTimeUs / 2d;
    assertThat(playAdsAfterTimeCaptor.getValue())
        .isWithin(0.1d)
        .of(expectedPlayAdsAfterTimeUs / C.MICROS_PER_SECOND);
    assertThat(adsLoaderListener.adPlaybackState)
        .isEqualTo(
            ImaUtil.getInitialAdPlaybackStateForCuePoints(cuePoints)
                .withSkippedAdGroup(/* adGroupIndex= */ 0)
                .withContentDurationUs(CONTENT_PERIOD_DURATION_US));
  }

  @Test
  public void resumePlaybackAtMidroll_withoutPlayAdBeforeStartPosition_skipsPreroll() {
    long midrollWindowTimeUs = 2 * C.MICROS_PER_SECOND;
    long midrollPeriodTimeUs =
        midrollWindowTimeUs + TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    ImmutableList<Float> cuePoints =
        ImmutableList.of(0f, (float) midrollPeriodTimeUs / C.MICROS_PER_SECOND);
    setupPlayback(
        CONTENT_TIMELINE,
        cuePoints,
        new ImaAdsLoader.Builder(getApplicationContext())
            .setPlayAdBeforeStartPosition(false)
            .setImaFactory(mockImaFactory)
            .setImaSdkSettings(mockImaSdkSettings)
            .build(),
        TEST_DATA_SPEC,
        TEST_ADS_ID);

    fakeExoPlayer.setPlayingContentPosition(C.usToMs(midrollWindowTimeUs));
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);

    ArgumentCaptor<Double> playAdsAfterTimeCaptor = ArgumentCaptor.forClass(Double.class);
    verify(mockAdsRenderingSettings).setPlayAdsAfterTime(playAdsAfterTimeCaptor.capture());
    double expectedPlayAdsAfterTimeUs = midrollPeriodTimeUs / 2d;
    assertThat(playAdsAfterTimeCaptor.getValue())
        .isWithin(0.1d)
        .of(expectedPlayAdsAfterTimeUs / C.MICROS_PER_SECOND);
    assertThat(adsLoaderListener.adPlaybackState)
        .isEqualTo(
            ImaUtil.getInitialAdPlaybackStateForCuePoints(cuePoints)
                .withContentDurationUs(CONTENT_PERIOD_DURATION_US)
                .withSkippedAdGroup(/* adGroupIndex= */ 0));
  }

  @Test
  public void resumePlaybackAfterMidroll_withoutPlayAdBeforeStartPosition_skipsMidroll() {
    long midrollWindowTimeUs = 2 * C.MICROS_PER_SECOND;
    long midrollPeriodTimeUs =
        midrollWindowTimeUs + TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    ImmutableList<Float> cuePoints =
        ImmutableList.of(0f, (float) midrollPeriodTimeUs / C.MICROS_PER_SECOND);
    setupPlayback(
        CONTENT_TIMELINE,
        cuePoints,
        new ImaAdsLoader.Builder(getApplicationContext())
            .setPlayAdBeforeStartPosition(false)
            .setImaFactory(mockImaFactory)
            .setImaSdkSettings(mockImaSdkSettings)
            .build(),
        TEST_DATA_SPEC,
        TEST_ADS_ID);

    fakeExoPlayer.setPlayingContentPosition(C.usToMs(midrollWindowTimeUs) + 1_000);
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);

    verify(mockAdsManager).destroy();
    assertThat(adsLoaderListener.adPlaybackState)
        .isEqualTo(
            ImaUtil.getInitialAdPlaybackStateForCuePoints(cuePoints)
                .withContentDurationUs(CONTENT_PERIOD_DURATION_US)
                .withSkippedAdGroup(/* adGroupIndex= */ 0)
                .withSkippedAdGroup(/* adGroupIndex= */ 1));
  }

  @Test
  public void
      resumePlaybackBeforeSecondMidroll_withoutPlayAdBeforeStartPosition_skipsFirstMidroll() {
    long firstMidrollWindowTimeUs = 2 * C.MICROS_PER_SECOND;
    long firstMidrollPeriodTimeUs =
        firstMidrollWindowTimeUs
            + TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    long secondMidrollWindowTimeUs = 4 * C.MICROS_PER_SECOND;
    long secondMidrollPeriodTimeUs =
        secondMidrollWindowTimeUs
            + TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    ImmutableList<Float> cuePoints =
        ImmutableList.of(
            (float) firstMidrollPeriodTimeUs / C.MICROS_PER_SECOND,
            (float) secondMidrollPeriodTimeUs / C.MICROS_PER_SECOND);
    setupPlayback(
        CONTENT_TIMELINE,
        cuePoints,
        new ImaAdsLoader.Builder(getApplicationContext())
            .setPlayAdBeforeStartPosition(false)
            .setImaFactory(mockImaFactory)
            .setImaSdkSettings(mockImaSdkSettings)
            .build(),
        TEST_DATA_SPEC,
        TEST_ADS_ID);

    fakeExoPlayer.setPlayingContentPosition(C.usToMs(secondMidrollWindowTimeUs) - 1_000);
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);

    ArgumentCaptor<Double> playAdsAfterTimeCaptor = ArgumentCaptor.forClass(Double.class);
    verify(mockAdsRenderingSettings).setPlayAdsAfterTime(playAdsAfterTimeCaptor.capture());
    double expectedPlayAdsAfterTimeUs = (firstMidrollPeriodTimeUs + secondMidrollPeriodTimeUs) / 2d;
    assertThat(playAdsAfterTimeCaptor.getValue())
        .isWithin(0.1d)
        .of(expectedPlayAdsAfterTimeUs / C.MICROS_PER_SECOND);
    assertThat(adsLoaderListener.adPlaybackState)
        .isEqualTo(
            ImaUtil.getInitialAdPlaybackStateForCuePoints(cuePoints)
                .withSkippedAdGroup(/* adGroupIndex= */ 0)
                .withContentDurationUs(CONTENT_PERIOD_DURATION_US));
  }

  @Test
  public void resumePlaybackAtSecondMidroll_withoutPlayAdBeforeStartPosition_skipsFirstMidroll() {
    long firstMidrollWindowTimeUs = 2 * C.MICROS_PER_SECOND;
    long firstMidrollPeriodTimeUs =
        firstMidrollWindowTimeUs
            + TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    long secondMidrollWindowTimeUs = 4 * C.MICROS_PER_SECOND;
    long secondMidrollPeriodTimeUs =
        secondMidrollWindowTimeUs
            + TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    ImmutableList<Float> cuePoints =
        ImmutableList.of(
            (float) firstMidrollPeriodTimeUs / C.MICROS_PER_SECOND,
            (float) secondMidrollPeriodTimeUs / C.MICROS_PER_SECOND);
    setupPlayback(
        CONTENT_TIMELINE,
        cuePoints,
        new ImaAdsLoader.Builder(getApplicationContext())
            .setPlayAdBeforeStartPosition(false)
            .setImaFactory(mockImaFactory)
            .setImaSdkSettings(mockImaSdkSettings)
            .build(),
        TEST_DATA_SPEC,
        TEST_ADS_ID);

    fakeExoPlayer.setPlayingContentPosition(C.usToMs(secondMidrollWindowTimeUs));
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);

    ArgumentCaptor<Double> playAdsAfterTimeCaptor = ArgumentCaptor.forClass(Double.class);
    verify(mockAdsRenderingSettings).setPlayAdsAfterTime(playAdsAfterTimeCaptor.capture());
    double expectedPlayAdsAfterTimeUs = (firstMidrollPeriodTimeUs + secondMidrollPeriodTimeUs) / 2d;
    assertThat(playAdsAfterTimeCaptor.getValue())
        .isWithin(0.1d)
        .of(expectedPlayAdsAfterTimeUs / C.MICROS_PER_SECOND);
    assertThat(adsLoaderListener.adPlaybackState)
        .isEqualTo(
            ImaUtil.getInitialAdPlaybackStateForCuePoints(cuePoints)
                .withContentDurationUs(CONTENT_PERIOD_DURATION_US)
                .withSkippedAdGroup(/* adGroupIndex= */ 0));
  }

  @Test
  public void requestAdTagWithDataScheme_requestsWithAdsResponse() throws Exception {
    String adsResponse =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<VAST xmlns:xsi=\"https://www.w3.org/2001/XMLSchema-instance\""
            + " xsi:noNamespaceSchemaLocation=\"vast.xsd\" version=\"2.0\">\n"
            + "  <Ad id=\"17180293\">\n"
            + "    <InLine></InLine>\n"
            + "  </Ad>\n"
            + "</VAST>";
    DataSpec adDataSpec = new DataSpec(Util.getDataUriForString("text/xml", adsResponse));

    setupPlayback(
        CONTENT_TIMELINE,
        ImmutableList.of(0f),
        new ImaAdsLoader.Builder(getApplicationContext())
            .setImaFactory(mockImaFactory)
            .setImaSdkSettings(mockImaSdkSettings)
            .build(),
        adDataSpec,
        TEST_ADS_ID);
    imaAdsLoader.start(adsMediaSource, adDataSpec, TEST_ADS_ID, adViewProvider, adsLoaderListener);

    verify(mockAdsRequest).setAdsResponse(adsResponse);
  }

  @Test
  public void requestAdTagWithUri_requestsWithAdTagUrl() throws Exception {
    setupPlayback(
        CONTENT_TIMELINE,
        ImmutableList.of(0f),
        new ImaAdsLoader.Builder(getApplicationContext())
            .setImaFactory(mockImaFactory)
            .setImaSdkSettings(mockImaSdkSettings)
            .build(),
        TEST_DATA_SPEC,
        TEST_ADS_ID);
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);

    verify(mockAdsRequest).setAdTagUrl(TEST_DATA_SPEC.uri.toString());
  }

  @Test
  public void setsDefaultMimeTypes() throws Exception {
    setupPlayback(CONTENT_TIMELINE, ImmutableList.of(0f));
    imaAdsLoader.setSupportedContentTypes(C.TYPE_DASH, C.TYPE_OTHER);
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);

    verify(mockAdsRenderingSettings)
        .setMimeTypes(
            ImmutableList.of(
                MimeTypes.APPLICATION_MPD,
                MimeTypes.VIDEO_MP4,
                MimeTypes.VIDEO_WEBM,
                MimeTypes.VIDEO_H263,
                MimeTypes.AUDIO_MP4,
                MimeTypes.AUDIO_MPEG));
  }

  @Test
  public void buildWithAdMediaMimeTypes_setsMimeTypes() throws Exception {
    setupPlayback(
        CONTENT_TIMELINE,
        ImmutableList.of(0f),
        new ImaAdsLoader.Builder(getApplicationContext())
            .setImaFactory(mockImaFactory)
            .setImaSdkSettings(mockImaSdkSettings)
            .setAdMediaMimeTypes(ImmutableList.of(MimeTypes.AUDIO_MPEG))
            .build(),
        TEST_DATA_SPEC,
        TEST_ADS_ID);
    imaAdsLoader.setSupportedContentTypes(C.TYPE_OTHER);
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);

    verify(mockAdsRenderingSettings).setMimeTypes(ImmutableList.of(MimeTypes.AUDIO_MPEG));
  }

  @Test
  public void stop_unregistersAllVideoControlOverlays() {
    setupPlayback(CONTENT_TIMELINE, PREROLL_CUE_POINTS_SECONDS);
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);
    imaAdsLoader.requestAds(TEST_DATA_SPEC, adViewGroup);
    imaAdsLoader.stop(adsMediaSource);

    InOrder inOrder = inOrder(mockAdDisplayContainer);
    inOrder.verify(mockAdDisplayContainer).registerFriendlyObstruction(mockFriendlyObstruction);
    inOrder.verify(mockAdDisplayContainer).unregisterAllFriendlyObstructions();
  }

  @Test
  public void loadAd_withLargeAdCuePoint_updatesAdPlaybackStateWithLoadedAd() {
    // Use a large enough value to test correct truncating of large cue points.
    float midrollTimeSecs = Float.MAX_VALUE;
    ImmutableList<Float> cuePoints = ImmutableList.of(midrollTimeSecs);
    setupPlayback(CONTENT_TIMELINE, cuePoints);
    imaAdsLoader.start(
        adsMediaSource, TEST_DATA_SPEC, TEST_ADS_ID, adViewProvider, adsLoaderListener);
    videoAdPlayer.loadAd(
        TEST_AD_MEDIA_INFO,
        new AdPodInfo() {
          @Override
          public int getTotalAds() {
            return 1;
          }

          @Override
          public int getAdPosition() {
            return 1;
          }

          @Override
          public boolean isBumper() {
            return false;
          }

          @Override
          public double getMaxDuration() {
            return 0;
          }

          @Override
          public int getPodIndex() {
            return 0;
          }

          @Override
          public double getTimeOffset() {
            return midrollTimeSecs;
          }
        });

    assertThat(adsLoaderListener.adPlaybackState)
        .isEqualTo(
            ImaUtil.getInitialAdPlaybackStateForCuePoints(cuePoints)
                .withContentDurationUs(CONTENT_PERIOD_DURATION_US)
                .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
                .withAdUri(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0, TEST_URI)
                .withAdDurationsUs(new long[][] {{TEST_AD_DURATION_US}}));
  }

  private void setupPlayback(Timeline contentTimeline, List<Float> cuePoints) {
    setupPlayback(
        contentTimeline,
        cuePoints,
        new ImaAdsLoader.Builder(getApplicationContext())
            .setImaFactory(mockImaFactory)
            .setImaSdkSettings(mockImaSdkSettings)
            .build(),
        TEST_DATA_SPEC,
        TEST_ADS_ID);
  }

  private void setupPlayback(
      Timeline contentTimeline,
      List<Float> cuePoints,
      ImaAdsLoader imaAdsLoader,
      DataSpec adTagDataSpec,
      Object adsId) {
    fakeExoPlayer = new FakePlayer();
    adsLoaderListener = new TestAdsLoaderListener(fakeExoPlayer, contentTimeline);
    adsMediaSource =
        new AdsMediaSource(
            new FakeMediaSource(new FakeTimeline(/* windowCount= */ 1)),
            adTagDataSpec,
            adsId,
            new DefaultMediaSourceFactory((Context) getApplicationContext()),
            imaAdsLoader,
            adViewProvider);
    when(mockAdsManager.getAdCuePoints()).thenReturn(cuePoints);
    this.imaAdsLoader = imaAdsLoader;
    imaAdsLoader.setPlayer(fakeExoPlayer);
  }

  private void setupMocks() {
    ArgumentCaptor<Object> userRequestContextCaptor = ArgumentCaptor.forClass(Object.class);
    doNothing().when(mockAdsRequest).setUserRequestContext(userRequestContextCaptor.capture());
    when(mockAdsRequest.getUserRequestContext())
        .thenAnswer(invocation -> userRequestContextCaptor.getValue());
    List<com.google.ads.interactivemedia.v3.api.AdsLoader.AdsLoadedListener> adsLoadedListeners =
        new ArrayList<>();
    // Deliberately don't handle removeAdsLoadedListener to allow testing behavior if the IMA SDK
    // invokes callbacks after release.
    doAnswer(
            invocation -> {
              adsLoadedListeners.add(invocation.getArgument(0));
              return null;
            })
        .when(mockAdsLoader)
        .addAdsLoadedListener(any());
    when(mockAdsManagerLoadedEvent.getAdsManager()).thenReturn(mockAdsManager);
    when(mockAdsManagerLoadedEvent.getUserRequestContext())
        .thenAnswer(invocation -> mockAdsRequest.getUserRequestContext());
    doAnswer(
            (Answer<Object>)
                invocation -> {
                  for (com.google.ads.interactivemedia.v3.api.AdsLoader.AdsLoadedListener listener :
                      adsLoadedListeners) {
                    listener.onAdsManagerLoaded(mockAdsManagerLoadedEvent);
                  }
                  return null;
                })
        .when(mockAdsLoader)
        .requestAds(mockAdsRequest);

    doAnswer(
            invocation -> {
              adEventListener = invocation.getArgument(0);
              return null;
            })
        .when(mockAdsManager)
        .addAdEventListener(any());

    doAnswer(
            invocation -> {
              contentProgressProvider = invocation.getArgument(0);
              return null;
            })
        .when(mockAdsRequest)
        .setContentProgressProvider(any());

    doAnswer(
            invocation -> {
              videoAdPlayer = invocation.getArgument(1);
              return mockAdDisplayContainer;
            })
        .when(mockImaFactory)
        .createAdDisplayContainer(any(), any());
    doAnswer(
            invocation -> {
              videoAdPlayer = invocation.getArgument(1);
              return mockAdDisplayContainer;
            })
        .when(mockImaFactory)
        .createAudioAdDisplayContainer(any(), any());
    when(mockImaFactory.createAdsRenderingSettings()).thenReturn(mockAdsRenderingSettings);
    when(mockImaFactory.createAdsRequest()).thenReturn(mockAdsRequest);
    when(mockImaFactory.createAdsLoader(any(), any(), any())).thenReturn(mockAdsLoader);
    when(mockImaFactory.createFriendlyObstruction(any(), any(), any()))
        .thenReturn(mockFriendlyObstruction);

    when(mockAdPodInfo.getPodIndex()).thenReturn(0);
    when(mockAdPodInfo.getTotalAds()).thenReturn(1);
    when(mockAdPodInfo.getAdPosition()).thenReturn(1);

    when(mockPrerollSingleAd.getAdPodInfo()).thenReturn(mockAdPodInfo);
  }

  private static AdEvent getAdEvent(AdEventType adEventType, @Nullable Ad ad) {
    return new AdEvent() {
      @Override
      public AdEventType getType() {
        return adEventType;
      }

      @Override
      @Nullable
      public Ad getAd() {
        return ad;
      }

      @Override
      public Map<String, String> getAdData() {
        return Collections.emptyMap();
      }
    };
  }

  /** Ad loader event listener that forwards ad playback state to a fake player. */
  private static final class TestAdsLoaderListener implements AdsLoader.EventListener {

    private final FakePlayer fakeExoPlayer;
    private final Timeline contentTimeline;

    public AdPlaybackState adPlaybackState;

    public TestAdsLoaderListener(FakePlayer fakeExoPlayer, Timeline contentTimeline) {
      this.fakeExoPlayer = fakeExoPlayer;
      this.contentTimeline = contentTimeline;
    }

    @Override
    public void onAdPlaybackState(AdPlaybackState adPlaybackState) {
      long[][] adDurationsUs = new long[adPlaybackState.adGroupCount][];
      for (int adGroupIndex = 0; adGroupIndex < adPlaybackState.adGroupCount; adGroupIndex++) {
        adDurationsUs[adGroupIndex] = new long[adPlaybackState.adGroups[adGroupIndex].uris.length];
        Arrays.fill(adDurationsUs[adGroupIndex], TEST_AD_DURATION_US);
      }
      adPlaybackState = adPlaybackState.withAdDurationsUs(adDurationsUs);
      this.adPlaybackState = adPlaybackState;
      fakeExoPlayer.updateTimeline(
          new SinglePeriodAdTimeline(contentTimeline, adPlaybackState),
          Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE);
    }

    @Override
    public void onAdLoadError(AdLoadException error, DataSpec dataSpec) {
      assertThat(error.type).isNotEqualTo(AdLoadException.TYPE_UNEXPECTED);
    }

    @Override
    public void onAdClicked() {
      // Do nothing.
    }

    @Override
    public void onAdTapped() {
      // Do nothing.
    }
  }
}
