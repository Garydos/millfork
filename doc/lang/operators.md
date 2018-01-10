# Operators

Unlike in high-level languages, operators in Millfork have limited applicability. 
Not every well-formed expression is actually compilable. 
Further improvements to the compiler may increase the number of acceptable combinations. 

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
They are interpeted as expected: `5 - 3 + 2 == 4` and `5 -' 3 +' 2 == 4`.  
Note that you cannot mix `+'` and `-'` with `+` and `-`. 

## Argument types

In the descriptions below, arguments to the operators are explained as follows:

* `byte` means any one-byte type

* `word` means any two-byte type, or a byte expanded to a word

* `long` means any type longer than two bytes, or a shorted type expanded to such length to match the other argument

* `constant` means a compile-time constant

* `simple` means either: a constant, a non-stack variable,
a pointer indexed with a constant, a pointer indexed with a non-stack variable, 
an array indexed with a constant, an array indexed with a non-stack variable, 
an array indexed with a sum of a constant and a non-stack variable, 
or a split-word expression made of two simple expressions. 
Examples: `1`, `a`, `p[2]`, `p[i]`, `arr[2]`, `arr[i]`, `arr[i+2]`, `h:l`, `h[i]:l[i]`
Such expressions have the property that the only register they may clobber is Y.

* `mutable` means an expression than can be assigned to

## Binary arithmetic operators

* `+`, `-`:  
`byte + byte`  
`word + word`  
`long + long`

* `*`: 8-bit multiplication  
`byte * constant byte`  
`constant byte * byte`

There are no division, remainder or modulo operators.

## Bitwise operators

* `|`, `^`, `&`: OR, EXOR and AND  
`byte | byte`

* `<<`, `>>`: bit shifting; shifting right pads the result with zeroes  
`byte << constant byte`

* `>>>>`: shifting a 9-bit value and returning a byte; `a >>>> b` is equivalent to `(a & $1FF) >> b`, but the latter doesn't compile yet  
`word >>>> constant byte`  

## Decimal arithmetic operators

These operators work using the decimal arithmetic and will not work on Ricoh CPU's.

* `+'`, `-'`: decimal addition/subtraction  
`byte +' byte`  
`word +' word`  
`long +' long`

* `*'`: decimal multiplication  
`constant *' constant`

* `<<'`, `>>'`: decimal multiplication/division by power of two  
`byte <<' constant byte`

## Comparison operators

These operators (except for `!=`) can accept more than 2 arguments. 
In such case, the result is true if each comparison in the group is true.
Note you cannot mix those operators, so `a <= b < c` is not valid.

* `==`: equality  
`byte == byte`  
`word == word`  
`long == long`  

* `!=`: inequality  
`byte != byte`  
`word != word`  
`long != long`

* `>`, `<`, `<=`, `>=`: inequality  
`byte > byte`  
`word > word`  
`long > long`  

Currently, `>`, `<`, `<=`, `>=` operators perform unsigned comparison 
if none of the types of their arguments is signed,
and fail to compile otherwise. This will be changed in the future.  

## Assignment operators

* `=`: normal assignment    
`mutable byte = byte`  
`mutable word = word`
`mutable long = long`

* `+=`, `+'=`, `|=`, `^=`, `&=`: modification in place  
`mutable byte += byte`  
`mutable word += word`  
`mutable long += long`

* `<<=`, `>>=`, `<<'=`, `>>'=`: shift in place  
`mutable byte <<= constant byte`  
`mutable word <<= constant byte`  
`mutable long <<= constant byte`

* `-=`, `-'=`: subtraction in place  
`mutable byte -= byte`  
`mutable word -= simple word`  
`mutable long -= simple long`

* `*=`: multiplication in place  
`mutable byte *= constant byte`

There are no `*'=` operator yet.