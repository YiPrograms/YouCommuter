package forecast

import (
	"math"
	"sort"
	"time"

	"youcommuter/backend/internal/station"
)

func surroundingKnots(horizon time.Duration) (int, int) {
	minutes := horizon.Minutes()
	lower := int(math.Floor(minutes/5)) * 5
	upper := int(math.Ceil(minutes/5)) * 5
	if math.Abs(minutes-float64(lower)) < 1e-9 {
		upper = lower
	}
	return lower, upper
}

type weightedOutcome struct {
	count  int
	weight float64
}

type knotDistribution struct {
	pmf     map[int]float64
	support Support
}

func buildKnot(
	snapshots []Snapshot,
	metric Metric,
	current int,
	capacity int,
	issueLocal time.Time,
	horizonMinutes int,
) knotDistribution {
	issueDate := localMidnight(issueLocal)
	issueSeconds := issueLocal.Hour()*3600 + issueLocal.Minute()*60 + issueLocal.Second()
	starts := analogStartSeconds(issueSeconds)
	pmf := make(map[int]float64)
	dayWeights := make([]float64, 0, HistoryDays)
	sameWeekday := 0
	for ageDays := 1; ageDays <= HistoryDays; ageDays++ {
		date := issueDate.AddDate(0, 0, -ageDays)
		if weekdayClass(date.Weekday()) != weekdayClass(issueDate.Weekday()) {
			continue
		}
		outcomes := make([]weightedOutcome, 0, len(starts))
		candidateWeight := 0.0
		for _, seconds := range starts {
			start := date.Add(time.Duration(seconds) * time.Second)
			if !start.Add(time.Duration(horizonMinutes) * time.Minute).Before(issueLocal) {
				continue
			}
			startValue, valid := canonicalMetric(snapshots, metric, start, capacity)
			if !valid || math.Abs(float64(startValue-current)/float64(capacity)) > 0.35 {
				continue
			}
			endValue, valid := validPath(
				snapshots, metric, start, time.Duration(horizonMinutes)*time.Minute, capacity,
			)
			if !valid {
				continue
			}
			offsetMinutes := math.Abs(float64(seconds-issueSeconds)) / 60
			timeWeight := 1 - offsetMinutes/20
			stockWeight := math.Exp(-math.Abs(float64(startValue-current)/float64(capacity)) / 0.20)
			weight := timeWeight * stockWeight
			if weight <= 0 {
				continue
			}
			predicted := min(capacity, max(0, current+endValue-startValue))
			outcomes = append(outcomes, weightedOutcome{count: predicted, weight: weight})
			candidateWeight += weight
		}
		if len(outcomes) == 0 || candidateWeight == 0 {
			continue
		}
		dayWeight := math.Pow(2, -float64(ageDays)/28)
		if date.Weekday() != issueDate.Weekday() {
			dayWeight *= 0.35
		} else {
			sameWeekday++
		}
		for _, outcome := range outcomes {
			pmf[outcome.count] += dayWeight * outcome.weight / candidateWeight
		}
		dayWeights = append(dayWeights, dayWeight)
	}
	total := 0.0
	for _, probability := range pmf {
		total += probability
	}
	if total > 0 {
		for count := range pmf {
			pmf[count] /= total
		}
	}
	return knotDistribution{
		pmf: pmf,
		support: Support{
			DistinctDays: len(dayWeights), EffectiveDays: effectiveSupport(dayWeights),
			SameWeekdayDays: sameWeekday,
		},
	}
}

func analogStartSeconds(issueSeconds int) []int {
	first := int(math.Ceil(float64(issueSeconds-int(analogWindow.Seconds()))/float64(canonicalStep.Seconds()))) * int(canonicalStep.Seconds())
	last := int(math.Floor(float64(issueSeconds+int(analogWindow.Seconds()))/float64(canonicalStep.Seconds()))) * int(canonicalStep.Seconds())
	first = max(0, first)
	last = min(24*60*60-int(canonicalStep.Seconds()), last)
	result := make([]int, 0, 7)
	for seconds := first; seconds <= last; seconds += int(canonicalStep.Seconds()) {
		result = append(result, seconds)
	}
	return result
}

func validPath(
	snapshots []Snapshot,
	metric Metric,
	start time.Time,
	horizon time.Duration,
	capacity int,
) (int, bool) {
	var value int
	for elapsed := time.Duration(0); elapsed <= horizon; elapsed += canonicalStep {
		var valid bool
		value, valid = canonicalMetric(snapshots, metric, start.Add(elapsed), capacity)
		if !valid {
			return 0, false
		}
	}
	return value, true
}

func canonicalMetric(snapshots []Snapshot, metric Metric, boundary time.Time, capacity int) (int, bool) {
	index := sort.Search(len(snapshots), func(i int) bool {
		return snapshots[i].ObservedAt.After(boundary)
	}) - 1
	if index < 0 {
		return 0, false
	}
	snapshot := snapshots[index]
	maxAge := min(
		2*time.Duration(snapshot.ExpectedIntervalSeconds)*time.Second+30*time.Second,
		10*time.Minute,
	)
	if snapshot.ExpectedIntervalSeconds <= 0 || boundary.Sub(snapshot.ObservedAt) < 0 || boundary.Sub(snapshot.ObservedAt) > maxAge || snapshot.Status != station.Operating {
		return 0, false
	}
	value := metric.count(snapshot.Counts)
	if value == nil || *value < 0 || *value > capacity {
		return 0, false
	}
	return *value, true
}

func weekdayClass(day time.Weekday) int {
	if day == time.Saturday || day == time.Sunday {
		return 1
	}
	return 0
}

func effectiveSupport(weights []float64) float64 {
	sum, squares := 0.0, 0.0
	for _, weight := range weights {
		sum += weight
		squares += weight * weight
	}
	if squares == 0 {
		return 0
	}
	return sum * sum / squares
}

func weakestSupport(knots map[int]knotDistribution) Support {
	if len(knots) == 0 {
		return Support{}
	}
	result := Support{DistinctDays: math.MaxInt, EffectiveDays: math.MaxFloat64, SameWeekdayDays: math.MaxInt}
	for _, knot := range knots {
		result.DistinctDays = min(result.DistinctDays, knot.support.DistinctDays)
		result.EffectiveDays = min(result.EffectiveDays, knot.support.EffectiveDays)
		result.SameWeekdayDays = min(result.SameWeekdayDays, knot.support.SameWeekdayDays)
	}
	return result
}

func arrivalDistribution(
	arrival ArrivalDistribution,
	observedAt time.Time,
	current int,
	knots map[int]knotDistribution,
) (map[int]float64, bool) {
	result := make(map[int]float64)
	for _, point := range arrival.Points {
		horizon := point.At.Sub(observedAt)
		lower, upper := surroundingKnots(horizon)
		lowerPMF, available := knotPMF(lower, current, knots)
		if !available {
			return nil, false
		}
		upperPMF, available := knotPMF(upper, current, knots)
		if !available {
			return nil, false
		}
		alpha := 0.0
		if upper != lower {
			alpha = (horizon.Minutes() - float64(lower)) / float64(upper-lower)
		}
		for count, probability := range lowerPMF {
			result[count] += point.Probability * (1 - alpha) * probability
		}
		for count, probability := range upperPMF {
			result[count] += point.Probability * alpha * probability
		}
	}
	return result, true
}

func knotPMF(minutes, current int, knots map[int]knotDistribution) (map[int]float64, bool) {
	if minutes == 0 {
		return map[int]float64{current: 1}, true
	}
	distribution, ok := knots[minutes]
	return distribution.pmf, ok && len(distribution.pmf) > 0
}

func quantile(pmf map[int]float64, probability float64) int {
	counts := make([]int, 0, len(pmf))
	for count := range pmf {
		if pmf[count] > 0 {
			counts = append(counts, count)
		}
	}
	sort.Ints(counts)
	cumulative := 0.0
	for _, count := range counts {
		cumulative += pmf[count]
		if cumulative+1e-12 >= probability {
			return count
		}
	}
	return counts[len(counts)-1]
}

func localMidnight(value time.Time) time.Time {
	local := value.In(taipei)
	return time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, taipei)
}

func intPointer(value int) *int { return &value }
