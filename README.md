# ring-middleware-format [![Continuous Integration status](https://secure.travis-ci.org/metosin/ring-middleware-format.png)](http://travis-ci.org/metosin/ring-middleware-format) [![Dependencies Status](http://jarkeeper.com/metosin/ring-middleware-format/status.png)](http://jarkeeper.com/metosin/ring-middleware-format)

This is a set of middlewares that can be used to deserialize parameters sent
in the :body of requests and serialize a Clojure data structure in the :body
of a response to some string or binary representation. It natively handles
JSON, YAML, Transit over JSON or Msgpack and Clojure (edn) but it can easily
be extended to other custom formats, both string and binary. It is intended
for the creation of RESTful APIs that do the right thing by default but are
flexible enough to handle most special cases.

## Installation ##

Latest stable version:

[![Clojars Project](http://clojars.org/metosin/ring-middleware-format/latest-version.svg)](http://clojars.org/metosin/ring-middleware-format)

Add this to your dependencies in `project.clj`.

## Features ##

- Ring compatible middleware, works with any web framework build on top of Ring
- Automatically parses requests and encodes responses according to Content-Type and Accept headers
- Automatically handles charset detection of requests bodies, even if the
charset given by the MIME type is absent or wrong (using ICU)
- Automatically selects and uses the right charset for the response according to the request header
- Varied formats handled out of the box (*JSON*, *YAML*, *EDN*, *Transit over JSON or Msgpack*)
- Pluggable system makes it easy to add to the standards encoders and
decoders custom ones (proprietary format, Protobuf, specific xml, csv, etc.)

## API Documentation ##

Full [API documentation](http://metosin.github.com/ring-middleware-format) is available.

## Summary ##

To get automatic deserialization and serialization for all supported formats
with sane defaults regarding headers and charsets, just do this:

```clojure
(ns my.app
  (:require [ring.middleware.format :refer [wrap-formats]]))

(def app
  (-> handler
      (wrap-formats)))
```

`wrap-format-format` accepts an optional `:formats` parameter, which is a
list of the formats that should be handled. The first format of the list is
also the default serializer used when no other solution can be found.
The defaults are:

```clojure
(wrap-format-format handler :formats [:json :edn :yaml :yaml-in-html :transit-json :transit-msgpack])
```

The available formats are:

- `:json` JSON with string keys in `:params` and `:body-params`
- `:json-kw` JSON with keywodized keys in `:params` and `:body-params`
- `:yaml` YAML format
- `:yaml-kw` YAML format with keywodized keys in `:params` and `:body-params`
- `:edn` edn (native Clojure format). It uses *clojure.tools.edn* and never
evals code, but uses the custom tags from `*data-readers*`
- `:yaml-in-html` yaml in a html page (useful for browser debugging)
- `:transit-json` Transit over JSON format
- `:transit-msgpack` Transit over Msgpack format

Your routes should return raw clojure data structures where everything inside
can be handled by the default encoders (no Java objects or fns mostly). If a
route returns a _String_, _File_, _InputStream_ or _nil_, nothing will be
done. If no format can be deduced from the **Accept** header or the format
specified is unknown, the first format in the vector will be used (JSON by default).

Please note the default JSON and YAML decoder do not keywordize their output
keys, if this is the behaviour you want (be careful about keywordizing user
input!), you should use something like:

```clojure
(wrap-formats handler :formats [:json-kw :edn :yaml-kw :yaml-in-html :transit-json :transit-msgpack])
```

See also [wrap-formats](http://metosin.github.com/ring-middleware-format/ring.middleware.format.html#var-wrap-formats)
docstring for help on customizing error handling.

## Usage ##

### Detailed Usage ###

You can separate the params and response middlewares. This allows you to use
them separately, or to customize their behavior, with specific error
handling for example. See the wrappers docstrings for more details.

```clojure
(ns my.app
  (:require [ring.middleware.format-params :refer [wrap-format-params]]
            [ring.middleware.format-response :refer [wrap-format-response]]))

(def app
  (-> handler
      (wrap-format-params)
      (wrap-format-response)))
```

### Custom formats ###

You can implement custom formats in two ways:

- If you want to slightly modify an existing wrapper you can just pass it an
argument to overload the default.  For example, this will cause all json
formatted responses to be encoded in *iso-latin-1*:

```clojure
(-> handler
  (wrap-json-response {:charset "ISO-8859-1"}))
```

- You can implement the wrapper from scratch by using either or both
`wrap-format-params` and `wrap-format-response`. For now, see the docs of each
and how the other formats were implemented for help doing this.

## Future Work ##

- CSV example
- XML example

## See Also ##

This module aims to be both easy to use and easy to extend to new formats.
However, it does not try to help with every aspect of building a RESTful API,
like proper error handling and method dispatching. If that is what you are
looking for, you could check the modules which function more like frameworks:

- [Compojure-api](https://github.com/metosin/compojure-api)

## License ##

Copyright © 2014-2015 [Metosin Oy](http://www.metosin.fi)<br>
Copyright (C) 2011, 2012, 2013, 2014 Nils Grunwald

Distributed under the Eclipse Public License, the same as Clojure.
