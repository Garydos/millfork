[< back to index](../index.md)

# Operators

Unlike in high-level languages, operators in Millfork have limited applicability. 
Not every well-formed expression is actually compilable. 
Most expressions involving single bytes compile, 
but for larger types usually you need to use in-place modification operators.  
Further improvements to the compiler may increase the number of acceptable combinations. 

Certain expressions require the commandline flag `-fzp-register` (`.ini` equivalent: `zeropage_register`) to be enabled.
They will be marked with (zpreg) next to them. 
The flag is enabled by default, but you can disable it if you need it.

## Precedence

Millfork has different operator precedence compared to most other languages. From highest to lowest it goes:

* `*`, `*'`

* `+`, `+'`, `-`, `-'`, `|`, `&`, `^`, `>>`, `>>'`, `<<`, `<<'`, `>>>>`

* `:`

* `==`, `!=`, `<`, `>`, `<=`, `>=`

* `&&`

* `||`

* assignment and in-place modification operators

You cannot use two different operators at the same precedence levels without using parentheses to disambiguate. 
It is to prevent confusion about whether `a + b & c << d` means `(a + b) & (c << d)` `((a + b) & c) << d` or something else.   
The only exceptions are `+` and `-`, and `+'` and `-'`. 
They are interpreted as expected: `5 - 3 + 2 == 4` and `5 -' 3 +' 2 == 4`.  
Note that you cannot mix `+'` and `-'` with `+` and `-`. 

## Argument types

In the descriptions below, arguments to the operators are explained as follows:

* `byte` means any one-byte type

* `word` means any two-byte type, or a byte expanded to a word

* `long` means any type longer than two bytes, or a shorter type expanded to such length to match the other argument

* `constant` means a compile-time constant

* `simple` means either: a constant, a non-stack variable,
a pointer indexed with a constant, a pointer indexed with a non-stack variable, 
an array indexed with a constant, an array indexed with a non-stack variable, 
an array indexed with a sum of a constant and a non-stack variable, 
or a split-word expression made of two simple expressions. 
Examples: `1`, `a`, `p[2]`, `p[i]`, `arr[2]`, `arr[i]`, `arr[i+2]`, `h:l`, `h[i]:l[i]`
Such expressions have the property that the only register they may clobber is Y.

* `mutable` means an expression that can be assigned to

## Split-word operator

Expressions of the shape `h:l` where `h` and `l` are of type byte, are considered expressions of type word.  
If and only if both `h` and `l` are assignable expressions, then `h:l` is also an assignable expression.

## Binary arithmetic operators

* `+`, `-`:  
`byte + byte`  
`constant word + constant word`  
`constant long + constant long`  
`constant word + byte`  
`word + word` (zpreg)

* `*`: multiplication; the size of the result is the same as the size of the arguments  
`byte * constant byte`  
`constant byte * byte`  
`constant word * constant word`  
`constant long * constant long`  
`byte * byte` (zpreg)

There are no division, remainder or modulo operators.

## Bitwise operators

* `|`, `^`, `&`: OR, EXOR and AND  
`byte | byte`  
`constant word | constant word`  
`constant long | constant long`  
`word | word` (zpreg)

* `<<`, `>>`: bit shifting; shifting pads the result with zeroes  
`byte << byte`  
`word << byte` (zpreg)  
`constant word << constant byte`  
`constant long << constant byte`

* `>>>>`: shifting a 9-bit value and returning a byte; `a >>>> b` is equivalent to `(a & $1FF) >> b`    
`word >>>> constant byte`

## Decimal arithmetic operators

These operators work using the decimal arithmetic and will not work on Ricoh CPU's.
The compiler issues a warning if these operators appear in the code.

* `+'`, `-'`: decimal addition/subtraction  
`byte +' byte`  
`constant word +' constant word`  
`constant long +' constant long`  
`word +' word` (zpreg)

* `*'`: decimal multiplication  
`constant *' constant`

* `<<'`, `>>'`: decimal multiplication/division by power of two  
`byte <<' constant byte`

## Comparison operators

These operators (except for `!=`) can accept more than 2 arguments. 
In such case, the result is true if each comparison in the group is true.
Note you cannot mix those operators, so `a <= b < c` is not valid.

Note that currently in cases like `a < f() < b`, `f()` will be evaluated twice!

* `==`: equality  
`byte == byte`  
`simple word == simple word`  
`simple long == simple long`

* `!=`: inequality  
`byte != byte`  
`simple word != simple word`  
`simple long != simple long`

* `>`, `<`, `<=`, `>=`: inequality  
`byte > byte`  
`simple word > simple word`  
`simple long > simple long`

Currently, `>`, `<`, `<=`, `>=` operators perform unsigned comparison 
if none of the types of their arguments is signed,
and fail to compile otherwise. This will be changed in the future.  

## Assignment and in-place modification operators

* `=`: normal assignment    
`mutable byte = byte`  
`mutable word = word`
`mutable long = long`

* `+=`, `+'=`, `|=`, `^=`, `&=`: modification in place  
`mutable byte += byte`  
`mutable word += word`  
`mutable long += long`

* `<<=`, `>>=`: shift in place  
`mutable byte <<= byte`  
`mutable word <<= byte`  
`mutable long <<= byte`

* `<<'=`, `>>'=`: decimal shift in place  
`mutable byte <<= constant byte`  
`mutable word <<= constant byte`  
`mutable long <<= constant byte`

* `-=`, `-'=`: subtraction in place  
`mutable byte -= byte`  
`mutable word -= simple word`  
`mutable long -= simple long`

* `*=`: multiplication in place  
`mutable byte *= constant byte`  
`mutable byte *= byte` (zpreg)

* `*'=`: decimal multiplication in place  
`mutable byte *'= constant byte`

## Indexing

While Millfork does not consider indexing an operator, this is a place as good as any to discuss it.

An expression of form `a[i]`, where `i` is an expression of type `byte`, is:

* when `a` is an array: an access to the `i`-th element of the array `a`

* when `a` is a pointer variable: an access to the byte in memory at address `a + i`

Those expressions are of type `byte`. If `a` is any other kind of expression, `a[i]` is invalid.

## Built-in functions

* `not`: negation of a boolean expression  
`not(bool)`

* `nonet`: expansion of an 8-bit operation to a 9-bit operation  
`nonet(byte + byte)`  
`nonet(byte +' byte)`  
`nonet(byte << constant byte)`  
`nonet(byte <<' constant byte)`  
Other kinds of expressions than the above (even `nonet(byte + byte + byte)`) will not work as expected.

* `hi`, `lo`: most/least significant byte of a word  
`hi(word)`



