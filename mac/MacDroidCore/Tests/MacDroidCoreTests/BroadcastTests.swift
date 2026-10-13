import Testing
@testable import MacDroidCore

@Suite struct BroadcastTests {
    @Test func everySubscriberGetsEveryValue() async throws {
        let broadcast = Broadcast<Int>(replayLatest: false)
        let a = Collector<Int>(), b = Collector<Int>()
        let streamA = broadcast.stream(), streamB = broadcast.stream()
        let tasks = [
            Task { for await value in streamA { await a.append(value) } },
            Task { for await value in streamB { await b.append(value) } },
        ]
        broadcast.yield(1)
        broadcast.yield(2)
        try await eventually {
            let (first, second) = (await a.items, await b.items)
            return first == [1, 2] && second == [1, 2]
        }
        tasks.forEach { $0.cancel() }
    }

    @Test func lateSubscriberGetsLatestWhenReplaying() async throws {
        let broadcast = Broadcast<String>(replayLatest: true, initial: "start")
        broadcast.yield("newest")
        var iterator = broadcast.stream().makeAsyncIterator()
        #expect(await iterator.next() == "newest")
    }

    @Test func noReplayWithoutReplayLatest() async throws {
        let broadcast = Broadcast<Int>(replayLatest: false)
        broadcast.yield(1)
        let seen = Collector<Int>()
        let stream = broadcast.stream()
        let task = Task { for await value in stream { await seen.append(value) } }
        broadcast.yield(2)
        try await eventually { await seen.items == [2] }
        task.cancel()
    }

    @Test func cancelledSubscriberIsRemoved() async throws {
        let broadcast = Broadcast<Int>(replayLatest: false)
        let stream = broadcast.stream()
        let task = Task { for await _ in stream {} }
        #expect(broadcast.subscriberCount == 1)
        task.cancel()
        try await eventually { broadcast.subscriberCount == 0 }
    }

    @Test func finishEndsEveryStream() async {
        let broadcast = Broadcast<Int>(replayLatest: false)
        let stream = broadcast.stream()
        broadcast.finish()
        var iterator = stream.makeAsyncIterator()
        #expect(await iterator.next() == nil)
    }
}
