/*
 * Copyright 2016 David Karnok
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hu.akarnokd.rxjava2.parallel;

import java.util.*;
import java.util.concurrent.Callable;

import org.reactivestreams.*;

import io.reactivex.*;
import io.reactivex.exceptions.Exceptions;
import io.reactivex.functions.*;
import io.reactivex.internal.functions.*;
import io.reactivex.internal.subscriptions.EmptySubscription;
import io.reactivex.internal.util.*;
import io.reactivex.plugins.RxJavaPlugins;

/**
 * Abstract base class for Parallel publishers that take an array of Subscribers.
 * <p>
 * Use {@code from()} to start processing a regular Publisher in 'rails'.
 * Use {@code runOn()} to introduce where each 'rail' shoud run on thread-vise.
 * Use {@code sequential()} to merge the sources back into a single Flowable.
 *
 * @param <T> the value type
 * @deprecated 0.14.4: moved to io.reactivex.parallel.ParallelFlowable in RxJava 2.0.5;
 * will be removed when RxJava 2.1 comes out
 */
@Deprecated
public abstract class ParallelFlowable<T> {

    /**
     * Subscribes an array of Subscribers to this ParallelFlowable and triggers
     * the execution chain for all 'rails'.
     *
     * @param subscribers the subscribers array to run in parallel, the number
     * of items must be equal to the parallelism level of this ParallelFlowable
     */
    public abstract void subscribe(Subscriber<? super T>[] subscribers);

    /**
     * Returns the number of expected parallel Subscribers.
     * @return the number of expected parallel Subscribers
     */
    public abstract int parallelism();

    /**
     * Validates the number of subscribers and returns true if their number
     * matches the parallelism level of this ParallelFlowable.
     *
     * @param subscribers the array of Subscribers
     * @return true if the number of subscribers equals to the parallelism level
     */
    protected final boolean validate(Subscriber<?>[] subscribers) {
        int p = parallelism();
        if (subscribers.length != p) {
            for (Subscriber<?> s : subscribers) {
                EmptySubscription.error(new IllegalArgumentException("parallelism = " + p + ", subscribers = " + subscribers.length), s);
            }
            return false;
        }
        return true;
    }

    /**
     * Take a Publisher and prepare to consume it on multiple 'rails' (number of CPUs)
     * in a round-robin fashion.
     * @param <T> the value type
     * @param source the source Publisher
     * @return the ParallelFlowable instance
     */
    public static <T> ParallelFlowable<T> from(Publisher<? extends T> source) {
        return from(source, Runtime.getRuntime().availableProcessors(), Flowable.bufferSize());
    }

    /**
     * Take a Publisher and prepare to consume it on parallallism number of 'rails' in a round-robin fashion.
     * @param <T> the value type
     * @param source the source Publisher
     * @param parallelism the number of parallel rails
     * @return the new ParallelFlowable instance
     */
    public static <T> ParallelFlowable<T> from(Publisher<? extends T> source, int parallelism) {
        return from(source, parallelism, Flowable.bufferSize());
    }

    /**
     * Take a Publisher and prepare to consume it on parallallism number of 'rails' ,
     * possibly ordered and round-robin fashion and use custom prefetch amount and queue
     * for dealing with the source Publisher's values.
     * @param <T> the value type
     * @param source the source Publisher
     * @param parallelism the number of parallel rails
     * @param prefetch the number of values to prefetch from the source
     * the source until there is a rail ready to process it.
     * @return the new ParallelFlowable instance
     */
    public static <T> ParallelFlowable<T> from(Publisher<? extends T> source,
            int parallelism, int prefetch) {
        if (parallelism <= 0) {
            throw new IllegalArgumentException("parallelism > 0 required but it was " + parallelism);
        }
        if (prefetch <= 0) {
            throw new IllegalArgumentException("prefetch > 0 required but it was " + prefetch);
        }

        ObjectHelper.requireNonNull(source, "queueSupplier");

        return new ParallelFromPublisher<T>(source, parallelism, prefetch);
    }

    /**
     * Maps the source values on each 'rail' to another value.
     * <p>
     * Note that the same mapper function may be called from multiple threads concurrently.
     * @param <R> the output value type
     * @param mapper the mapper function turning Ts into Us.
     * @return the new ParallelFlowable instance
     */
    public final <R> ParallelFlowable<R> map(Function<? super T, ? extends R> mapper) {
        ObjectHelper.requireNonNull(mapper, "mapper");
        return new ParallelMap<T, R>(this, mapper);
    }

    /**
     * Filters the source values on each 'rail'.
     * <p>
     * Note that the same predicate may be called from multiple threads concurrently.
     * @param predicate the function returning true to keep a value or false to drop a value
     * @return the new ParallelFlowable instance
     */
    public final ParallelFlowable<T> filter(Predicate<? super T> predicate) {
        ObjectHelper.requireNonNull(predicate, "predicate");
        return new ParallelFilter<T>(this, predicate);
    }

    /**
     * Specifies where each 'rail' will observe its incoming values with
     * no work-stealing and default prefetch amount.
     * <p>
     * This operator uses the default prefetch size returned by {@code Flowable.bufferSize()}.
     * <p>
     * The operator will call {@code Scheduler.createWorker()} as many
     * times as this ParallelFlowable's parallelism level is.
     * <p>
     * No assumptions are made about the Scheduler's parallelism level,
     * if the Scheduler's parallelism level is lwer than the ParallelFlowable's,
     * some rails may end up on the same thread/worker.
     * <p>
     * This operator doesn't require the Scheduler to be trampolining as it
     * does its own built-in trampolining logic.
     *
     * @param scheduler the scheduler to use
     * @return the new ParallelFlowable instance
     */
    public final ParallelFlowable<T> runOn(Scheduler scheduler) {
        return runOn(scheduler, Flowable.bufferSize());
    }

    /**
     * Specifies where each 'rail' will observe its incoming values with
     * possibly work-stealing and a given prefetch amount.
     * <p>
     * This operator uses the default prefetch size returned by {@code Flowable.bufferSize()}.
     * <p>
     * The operator will call {@code Scheduler.createWorker()} as many
     * times as this ParallelFlowable's parallelism level is.
     * <p>
     * No assumptions are made about the Scheduler's parallelism level,
     * if the Scheduler's parallelism level is lwer than the ParallelFlowable's,
     * some rails may end up on the same thread/worker.
     * <p>
     * This operator doesn't require the Scheduler to be trampolining as it
     * does its own built-in trampolining logic.
     *
     * @param scheduler the scheduler to use
     * that rail's worker has run out of work.
     * @param prefetch the number of values to request on each 'rail' from the source
     * @return the new ParallelFlowable instance
     */
    public final ParallelFlowable<T> runOn(Scheduler scheduler, int prefetch) {
        if (prefetch <= 0) {
            throw new IllegalArgumentException("prefetch > 0 required but it was " + prefetch);
        }
        ObjectHelper.requireNonNull(scheduler, "scheduler");
        return new ParallelRunOn<T>(this, scheduler, prefetch);
    }

    /**
     * Reduces all values within a 'rail' and across 'rails' with a reducer function into a single
     * sequential value.
     * <p>
     * Note that the same reducer function may be called from multiple threads concurrently.
     * @param reducer the function to reduce two values into one.
     * @return the new Px instance emitting the reduced value or empty if the ParallelFlowable was empty
     */
    public final Flowable<T> reduce(BiFunction<T, T, T> reducer) {
        ObjectHelper.requireNonNull(reducer, "reducer");
        return RxJavaPlugins.onAssembly(new ParallelReduceFull<T>(this, reducer));
    }

    /**
     * Reduces all values within a 'rail' to a single value (with a possibly different type) via
     * a reducer function that is initialized on each rail from an initialSupplier value.
     * <p>
     * Note that the same mapper function may be called from multiple threads concurrently.
     * @param <R> the reduced output type
     * @param initialSupplier the supplier for the initial value
     * @param reducer the function to reduce a previous output of reduce (or the initial value supplied)
     * with a current source value.
     * @return the new ParallelFlowable instance
     */
    public final <R> ParallelFlowable<R> reduce(Callable<R> initialSupplier, BiFunction<R, T, R> reducer) {
        ObjectHelper.requireNonNull(initialSupplier, "initialSupplier");
        ObjectHelper.requireNonNull(reducer, "reducer");
        return new ParallelReduce<T, R>(this, initialSupplier, reducer);
    }

    /**
     * Merges the values from each 'rail' in a round-robin or same-order fashion and
     * exposes it as a regular Publisher sequence, running with a default prefetch value
     * for the rails.
     * <p>
     * This operator uses the default prefetch size returned by {@code Flowable.bufferSize()}.
     * @return the new Px instance
     * @see ParallelFlowable#sequential(int)
     */
    public final Flowable<T> sequential() {
        return sequential(Flowable.bufferSize());
    }

    /**
     * Merges the values from each 'rail' in a round-robin or same-order fashion and
     * exposes it as a regular Publisher sequence, running with a give prefetch value
     * for the rails.
     * @param prefetch the prefetch amount to use for each rail
     * @return the new Px instance
     * @see ParallelFlowable#sequential()
     */
    public final Flowable<T> sequential(int prefetch) {
        if (prefetch <= 0) {
            throw new IllegalArgumentException("prefetch > 0 required but it was " + prefetch);
        }
        return RxJavaPlugins.onAssembly(new ParallelJoin<T>(this, prefetch));
    }

    /**
     * Sorts the 'rails' of this ParallelFlowable and returns a Publisher that sequentially
     * picks the smallest next value from the rails.
     * <p>
     * This operator requires a finite source ParallelFlowable.
     *
     * @param comparator the comparator to use
     * @return the new Px instance
     */
    public final Flowable<T> sorted(Comparator<? super T> comparator) {
        return sorted(comparator, 16);
    }

    /**
     * Sorts the 'rails' of this ParallelFlowable and returns a Publisher that sequentially
     * picks the smallest next value from the rails.
     * <p>
     * This operator requires a finite source ParallelFlowable.
     *
     * @param comparator the comparator to use
     * @param capacityHint the expected number of total elements
     * @return the new Px instance
     */
    public final Flowable<T> sorted(Comparator<? super T> comparator, int capacityHint) {
        int ch = capacityHint / parallelism() + 1;
        ParallelFlowable<List<T>> railReduced = reduce(Functions.<T>createArrayList(ch), ListAddBiConsumer.<T>instance());
        ParallelFlowable<List<T>> railSorted = railReduced.map(new SorterFunction<T>(comparator));

        return RxJavaPlugins.onAssembly(new ParallelSortedJoin<T>(railSorted, comparator));
    }

    /**
     * Sorts the 'rails' according to the comparator and returns a full sorted list as a Publisher.
     * <p>
     * This operator requires a finite source ParallelFlowable.
     *
     * @param comparator the comparator to compare elements
     * @return the new Px instannce
     */
    public final Flowable<List<T>> toSortedList(Comparator<? super T> comparator) {
        return toSortedList(comparator, 16);
    }
    /**
     * Sorts the 'rails' according to the comparator and returns a full sorted list as a Publisher.
     * <p>
     * This operator requires a finite source ParallelFlowable.
     *
     * @param comparator the comparator to compare elements
     * @param capacityHint the expected number of total elements
     * @return the new Px instannce
     */
    public final Flowable<List<T>> toSortedList(Comparator<? super T> comparator, int capacityHint) {
        int ch = capacityHint / parallelism() + 1;
        ParallelFlowable<List<T>> railReduced = reduce(Functions.<T>createArrayList(ch), ListAddBiConsumer.<T>instance());
        ParallelFlowable<List<T>> railSorted = railReduced.map(new SorterFunction<T>(comparator));

        Flowable<List<T>> merged = railSorted.reduce(new MergerBiFunction<T>(comparator));

        return RxJavaPlugins.onAssembly(merged);
    }

    /**
     * Call the specified consumer with the current element passing through any 'rail'.
     *
     * @param onNext the callback
     * @return the new ParallelFlowable instance
     */
    public final ParallelFlowable<T> doOnNext(Consumer<? super T> onNext) {
        return new ParallelPeek<T>(this,
                onNext,
                Functions.emptyConsumer(),
                Functions.emptyConsumer(),
                Functions.EMPTY_ACTION,
                Functions.EMPTY_ACTION,
                Functions.emptyConsumer(),
                Functions.EMPTY_LONG_CONSUMER,
                Functions.EMPTY_ACTION
                );
    }

    /**
     * Call the specified consumer with the current element passing through any 'rail'
     * after it has been delivered to downstream within the rail.
     *
     * @param onAfterNext the callback
     * @return the new ParallelFlowable instance
     */
    public final ParallelFlowable<T> doAfterNext(Consumer<? super T> onAfterNext) {
        return new ParallelPeek<T>(this,
                Functions.emptyConsumer(),
                onAfterNext,
                Functions.emptyConsumer(),
                Functions.EMPTY_ACTION,
                Functions.EMPTY_ACTION,
                Functions.emptyConsumer(),
                Functions.EMPTY_LONG_CONSUMER,
                Functions.EMPTY_ACTION
                );
    }

    /**
     * Call the specified consumer with the exception passing through any 'rail'.
     *
     * @param onError the callback
     * @return the new ParallelFlowable instance
     */
    public final ParallelFlowable<T> doOnError(Consumer<Throwable> onError) {
        return new ParallelPeek<T>(this,
                Functions.emptyConsumer(),
                Functions.emptyConsumer(),
                onError,
                Functions.EMPTY_ACTION,
                Functions.EMPTY_ACTION,
                Functions.emptyConsumer(),
                Functions.EMPTY_LONG_CONSUMER,
                Functions.EMPTY_ACTION
                );
    }

    /**
     * Run the specified Action when a 'rail' completes.
     *
     * @param onComplete the callback
     * @return the new ParallelFlowable instance
     */
    public final ParallelFlowable<T> doOnComplete(Action onComplete) {
        return new ParallelPeek<T>(this,
                Functions.emptyConsumer(),
                Functions.emptyConsumer(),
                Functions.emptyConsumer(),
                onComplete,
                Functions.EMPTY_ACTION,
                Functions.emptyConsumer(),
                Functions.EMPTY_LONG_CONSUMER,
                Functions.EMPTY_ACTION
                );
    }

    /**
     * Run the specified Action when a 'rail' completes or signals an error.
     *
     * @param onAfterTerminate the callback
     * @return the new ParallelFlowable instance
     */
    public final ParallelFlowable<T> doAfterTerminated(Action onAfterTerminate) {
        return new ParallelPeek<T>(this,
                Functions.emptyConsumer(),
                Functions.emptyConsumer(),
                Functions.emptyConsumer(),
                Functions.EMPTY_ACTION,
                onAfterTerminate,
                Functions.emptyConsumer(),
                Functions.EMPTY_LONG_CONSUMER,
                Functions.EMPTY_ACTION
                );
    }

    /**
     * Call the specified callback when a 'rail' receives a Subscription from its upstream.
     *
     * @param onSubscribe the callback
     * @return the new ParallelFlowable instance
     */
    public final ParallelFlowable<T> doOnSubscribe(Consumer<? super Subscription> onSubscribe) {
        return new ParallelPeek<T>(this,
                Functions.emptyConsumer(),
                Functions.emptyConsumer(),
                Functions.emptyConsumer(),
                Functions.EMPTY_ACTION,
                Functions.EMPTY_ACTION,
                onSubscribe,
                Functions.EMPTY_LONG_CONSUMER,
                Functions.EMPTY_ACTION
                );
    }

    /**
     * Call the specified consumer with the request amount if any rail receives a request.
     *
     * @param onRequest the callback
     * @return the new ParallelFlowable instance
     */
    public final ParallelFlowable<T> doOnRequest(LongConsumer onRequest) {
        return new ParallelPeek<T>(this,
                Functions.emptyConsumer(),
                Functions.emptyConsumer(),
                Functions.emptyConsumer(),
                Functions.EMPTY_ACTION,
                Functions.EMPTY_ACTION,
                Functions.emptyConsumer(),
                onRequest,
                Functions.EMPTY_ACTION
                );
    }

    /**
     * Run the specified Action when a 'rail' receives a cancellation.
     *
     * @param onCancel the callback
     * @return the new ParallelFlowable instance
     */
    public final ParallelFlowable<T> doOnCancel(Action onCancel) {
        return new ParallelPeek<T>(this,
                Functions.emptyConsumer(),
                Functions.emptyConsumer(),
                Functions.emptyConsumer(),
                Functions.EMPTY_ACTION,
                Functions.EMPTY_ACTION,
                Functions.emptyConsumer(),
                Functions.EMPTY_LONG_CONSUMER,
                onCancel
                );
    }

    /**
     * Collect the elements in each rail into a collection supplied via a collectionSupplier
     * and collected into with a collector action, emitting the collection at the end.
     *
     * @param <C> the collection type
     * @param collectionSupplier the supplier of the collection in each rail
     * @param collector the collector, taking the per-rali collection and the current item
     * @return the new ParallelFlowable instance
     */
    public final <C> ParallelFlowable<C> collect(Callable<C> collectionSupplier, BiConsumer<C, T> collector) {
        return new ParallelCollect<T, C>(this, collectionSupplier, collector);
    }

    /**
     * Wraps multiple Publishers into a ParallelFlowable which runs them
     * in parallel and unordered.
     *
     * @param <T> the value type
     * @param publishers the array of publishers
     * @return the new ParallelFlowable instance
     */
    public static <T> ParallelFlowable<T> fromArray(Publisher<T>... publishers) {
        if (publishers.length == 0) {
            throw new IllegalArgumentException("Zero publishers not supported");
        }
        return new ParallelFromArray<T>(publishers);
    }

    /**
     * Perform a fluent transformation to a value via a converter function which
     * receives this ParallelFlowable.
     *
     * @param <U> the output value type
     * @param converter the converter function from ParallelFlowable to some type
     * @return the value returned by the converter function
     */
    public final <U> U to(Function<? super ParallelFlowable<T>, U> converter) {
        try {
            return converter.apply(this);
        } catch (Throwable ex) {
            Exceptions.throwIfFatal(ex);
            throw ExceptionHelper.wrapOrThrow(ex);
        }
    }

    /**
     * Allows composing operators, in assembly time, on top of this ParallelFlowable
     * and returns another ParallelFlowable with composed features.
     *
     * @param <U> the output value type
     * @param composer the composer function from ParallelFlowable (this) to another ParallelFlowable
     * @return the ParallelFlowable returned by the function
     */
    public final <U> ParallelFlowable<U> compose(Function<? super ParallelFlowable<T>, ParallelFlowable<U>> composer) {
        return to(composer);
    }

    /**
     * Generates and flattens Publishers on each 'rail'.
     * <p>
     * Errors are not delayed and uses unbounded concurrency along with default inner prefetch.
     *
     * @param <R> the result type
     * @param mapper the function to map each rail's value into a Publisher
     * @return the new ParallelFlowable instance
     */
    public final <R> ParallelFlowable<R> flatMap(Function<? super T, ? extends Publisher<? extends R>> mapper) {
        return flatMap(mapper, false, Integer.MAX_VALUE, Flowable.bufferSize());
    }

    /**
     * Generates and flattens Publishers on each 'rail', optionally delaying errors.
     * <p>
     * It uses unbounded concurrency along with default inner prefetch.
     *
     * @param <R> the result type
     * @param mapper the function to map each rail's value into a Publisher
     * @param delayError should the errors from the main and the inner sources delayed till everybody terminates?
     * @return the new ParallelFlowable instance
     */
    public final <R> ParallelFlowable<R> flatMap(
            Function<? super T, ? extends Publisher<? extends R>> mapper, boolean delayError) {
        return flatMap(mapper, delayError, Integer.MAX_VALUE, Flowable.bufferSize());
    }

    /**
     * Generates and flattens Publishers on each 'rail', optionally delaying errors
     * and having a total number of simultaneous subscriptions to the inner Publishers.
     * <p>
     * It uses a default inner prefetch.
     *
     * @param <R> the result type
     * @param mapper the function to map each rail's value into a Publisher
     * @param delayError should the errors from the main and the inner sources delayed till everybody terminates?
     * @param maxConcurrency the maximum number of simultaneous subscriptions to the generated inner Publishers
     * @return the new ParallelFlowable instance
     */
    public final <R> ParallelFlowable<R> flatMap(
            Function<? super T, ? extends Publisher<? extends R>> mapper, boolean delayError, int maxConcurrency) {
        return flatMap(mapper, delayError, maxConcurrency, Flowable.bufferSize());
    }

    /**
     * Generates and flattens Publishers on each 'rail', optionally delaying errors,
     * having a total number of simultaneous subscriptions to the inner Publishers
     * and using the given prefetch amount for the inner Publishers.
     *
     * @param <R> the result type
     * @param mapper the function to map each rail's value into a Publisher
     * @param delayError should the errors from the main and the inner sources delayed till everybody terminates?
     * @param maxConcurrency the maximum number of simultaneous subscriptions to the generated inner Publishers
     * @param prefetch the number of items to prefetch from each inner Publisher
     * @return the new ParallelFlowable instance
     */
    public final <R> ParallelFlowable<R> flatMap(
            Function<? super T, ? extends Publisher<? extends R>> mapper,
            boolean delayError, int maxConcurrency, int prefetch) {
        return new ParallelFlatMap<T, R>(this, mapper, delayError, maxConcurrency, prefetch);
    }

    /**
     * Generates and concatenates Publishers on each 'rail', signalling errors immediately
     * and generating 2 publishers upfront.
     *
     * @param <R> the result type
     * @param mapper the function to map each rail's value into a Publisher
     * source and the inner Publishers (immediate, boundary, end)
     * @return the new ParallelFlowable instance
     */
    public final <R> ParallelFlowable<R> concatMap(
            Function<? super T, ? extends Publisher<? extends R>> mapper) {
        return concatMap(mapper, 2, ErrorMode.IMMEDIATE);
    }

    /**
     * Generates and concatenates Publishers on each 'rail', signalling errors immediately
     * and using the given prefetch amount for generating Publishers upfront.
     *
     * @param <R> the result type
     * @param mapper the function to map each rail's value into a Publisher
     * @param prefetch the number of items to prefetch from each inner Publisher
     * source and the inner Publishers (immediate, boundary, end)
     * @return the new ParallelFlowable instance
     */
    public final <R> ParallelFlowable<R> concatMap(
            Function<? super T, ? extends Publisher<? extends R>> mapper,
                    int prefetch) {
        return concatMap(mapper, prefetch, ErrorMode.IMMEDIATE);
    }

    /**
     * Generates and concatenates Publishers on each 'rail', optionally delaying errors
     * and generating 2 publishers upfront.
     *
     * @param <R> the result type
     * @param mapper the function to map each rail's value into a Publisher
     * @param errorMode the error handling, i.e., when to report errors from the main
     * source and the inner Publishers (immediate, boundary, end)
     * @return the new ParallelFlowable instance
     */
    public final <R> ParallelFlowable<R> concatMap(
            Function<? super T, ? extends Publisher<? extends R>> mapper,
                    ErrorMode errorMode) {
        return concatMap(mapper, 2, errorMode);
    }

    /**
     * Generates and concatenates Publishers on each 'rail', optionally delaying errors
     * and using the given prefetch amount for generating Publishers upfront.
     *
     * @param <R> the result type
     * @param mapper the function to map each rail's value into a Publisher
     * @param prefetch the number of items to prefetch from each inner Publisher
     * @param errorMode the error handling, i.e., when to report errors from the main
     * source and the inner Publishers (immediate, boundary, end)
     * @return the new ParallelFlowable instance
     */
    public final <R> ParallelFlowable<R> concatMap(
            Function<? super T, ? extends Publisher<? extends R>> mapper,
                    int prefetch, ErrorMode errorMode) {
        return new ParallelConcatMap<T, R>(this, mapper, prefetch, errorMode);
    }
}
