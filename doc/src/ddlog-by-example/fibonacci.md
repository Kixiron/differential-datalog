# Fibonacci

The [fibonacci sequence] is a sequence of numbers where every element in the sequence is
calculated by the sum of the two proceeding numbers. For reference, here's a basic Python
implementation

```python
def fibonacci(n: int) -> int:
    if n == 0:
        return 0
    elif n == 1:
        return 1
    else:
        return fibonacci(n - 1) + fibonacci(n - 2)

print(fibonacci(1))  # 1
print(fibonacci(10)) # 55
print(fibonacci(50)) # 6765
```

There's two different ways we could approach this: One is through expressions and one is
through "pure ddlog". We'll start with the expression-based approach, since it's a lot
closer to the given python code.

```ddlog
input relation FibonacciFor(n: usize)

output relation Fibonacci(n: usize, fibonacci: usize)
Fibonacci(n, fibonacci(n)) :- FibonacciFor(n).

function fibonacci(n: usize): usize {
    match (n) {
        0 -> 0,
        1 -> 1,
        n -> fibonacci(n - 1) + fibonacci(n - 2),
    }
}
```

First off we have our input relation, `FibonacciFor`. This is where we'll put all the numbers
that we need to compute the fibonacci sequence for. Next we have our `Fibonacci` output relation
that'll spit out all the numbers we requested (`n`) and their corresponding fibonacci number
(`fibonacci`). After that we have a single clause which inserts the requested number and calls
the `fibonacci()` function for every record in `FibonacciFor`. After this is the meat of the program,
the `fibonacci()` function itself. DDlog functions must declare their parameter and return types,
which is convenient since it allows us to see what's going on at a glance: the function takes in a single
`usize` and returns another `usize`.

With the ~~boring~~ normal implementation out of the way, lets get to the interesting part:
Calculating the fibonacci sequence with nothing but relations.

At a basic level, the fibonacci sequence has a two requirements that any implementation must fulfill:

- Collect all numbers less than `n`
- Calculate the fibonacci sequence for `n` as well as all numbers less than it

When broken down like this, the road to making a ddlog implementation is a little more clear,
so let's get to it!

To start off with, we'll have the same input relation as before, this is where we'll request numbers
to be fibonacci'd

```ddlog
input relation FibonacciFor(n: usize)
```

Next we need to write the first constraint and collect all numbers less than (or equal to) `n`, with
`n` being our `FibonacciFor` relation

```ddlog
relation NeedsFibonacci(n: usize)
NeedsFibonacci(n) :- FibonacciFor(n).
NeedsFibonacci(n - 1) :- NeedsFibonacci(n), n >= 1.
NeedsFibonacci(n - 2) :- NeedsFibonacci(n), n >= 2.
```

After we have all of our numbers, we need to get the fibonacci sequence for each of them. Initially this
may seem like a very difficult problem to solve, but ddlog makes it easy by allowing [recursive relations].
For every `n` in `NeedsFibonacci`, we'll simply get the fibonacci number for `n - 1` and `n - 2`, making sure
to add in the base facts of `fib(0) = 0` and `fib(1) = 1`

```ddlog
relation FibonacciLoop(n: usize, fibonacci: usize)

// Add our base cases of `fib(0) = 0` and `fib(1) = 1`
FibonacciLoop(0, 0).
FibonacciLoop(1, 1).

FibonacciLoop(n, fib1 + fib2) :-
    // For every `n` in `NeedsFibonacci`
    NeedsFibonacci(n),
    // Get the fibonacci of `n - 1` and `n - 2`
    FibonacciLoop(n - 1, fib1),
    FibonacciLoop(n - 2, fib2).
```

And bam, we now have the fibonacci sequence for `n` as well as every number less than n! Our next
and final step is to output our requested fibonacci numbers, we could output `FibonacciLoop` directly
but that would output all of the fibonacci numbers instead of just the ones we requested in `FibonacciFor`.
The solution to this is easy enough, we just [join] `FibonacciLoop(n, _)` on `FibonacciFor(n)` to filter
out any numbers we didn't explicitly request

```ddlog
output relation Fibonacci(n: usize, fibonacci: usize)
Fibonacci(n, fib) :-
    FibonacciLoop(n, fib),
    FibonacciFor(n).
```

And that's it, we've implemented the fibonacci sequence in pure ddlog! Some neat extensions of this could be to
replace the usages of `usize` with ddlog's [`bigint`] which would allow computing arbitrarily large fibonacci numbers.

This ddlog implementation of the fibonacci sequence does have one advantage over the normal one: incremental computation.
If you were to calculate the `fib(10)` and `fib(11)` with a normal implementation, you would end up calculating `fib(0..=10)`
*twice* while ddlog will only ever calculate it *once*, no matter how many of the requested numbers depend on it. While this
probably doesn't matter for small numbers like 10 and 11, this could potentially have a massive performance impact for
incredibly large numbers or incredibly frequently requested ones.

<details>
<summary>Full Code</summary>

```ddlog
input relation FibonacciFor(n: usize)

relation NeedsFibonacci(n: usize)
NeedsFibonacci(n) :- FibonacciFor(n).
NeedsFibonacci(n - 1) :- NeedsFibonacci(n), n >= 1.
NeedsFibonacci(n - 2) :- NeedsFibonacci(n), n >= 2.

relation FibonacciLoop(n: usize, fibonacci: usize)
FibonacciLoop(0, 0).
FibonacciLoop(1, 1).
FibonacciLoop(n, fib1 + fib2) :-
    NeedsFibonacci(n),
    FibonacciLoop(n - 1, fib1),
    FibonacciLoop(n - 2, fib2).

output relation Fibonacci(n: usize, fibonacci: usize)
Fibonacci(n, fib) :-
    FibonacciLoop(n, fib),
    FibonacciFor(n).
```

</details>

[fibonacci sequence]: https://en.wikipedia.org/wiki/Fibonacci_number
