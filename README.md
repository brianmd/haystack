# Description
This project performs ecommerce searches. It extracts data from our mysql database into elasticsearch. It then accepts rest api requests for searches. The request and response are suited to our domain rather than following elasticsearch's api. In particular, the response is intended to provide complete information such that the browser can display the search results without needing to make a return trip for more information.

Processing a request follows these steps:
* parse the url query parameters into a query-map,
* build two logical query-maps into two elasticsearch queries: main and secondary queries. The two queries are similar except the main query also collects aggregation information. There are _Entire Site_ and _Local Stock_ tabs on the UI. The main query corresponds to whichever tab the user is on (which is relayed through the api via _:query-entire_ flag),
* transform the two logical query-maps into two elasticsearch queries,
* the two queries are sent to elasticsearch. As an aside, these are run in parallel threads, but should be modified to send both queries in one request,
* and finally, elasticsearch's responses are cherry picked for the information needed by the browser.

Further information on the request and response are below.

![Haystack transforms](/docs/haystack.jpg)

## Breaking down the url
For url: http://localhost:8080/api/v2/search?search=copper%20blue&category-path=/395&num-per-page=25&page-num=1&service-center-id=7&manufacturer-ids=[127,199]&query-entire=false

* http://localhost:8080 is the scheme, host, and port,
* /api/v2/search is the path to the search parser,
* __search:__ in this case "copper" and "blue" will be searched for,
* __category-path:__ 395 is the id for "Fuses, Batteries, Signaling, Connecting, Fastening & Tools", and only products in that category or one of its subcategories will be returned. Had the path been "/395/84", only products at or below the id=84,category="Fuses" will be returned. I.e., no products from "/395/88" (Flashlights & Batteries) will be returned,
* __num-per-page:__ number of products to return,
* __page_num:__ page number to return. For example, if num-per-page=10 and page-num=1 (note: this is one-based, not zero-based), products 1-10 will be returned. However, page-num=2 will return products 11-20,
* __service-center-id:__ id 7 equates to Albuquerque's service center,
* __manufacturer-ids:__ only products manufactured by Ilsco (127) or 3M Electrical Products (199) will be returned.
* __query-entire:__ if true (or if no service-center-id is passed) the main query will return products from the entire site, otherwise only products in stock at the selected service-center's are returned. The secondary query will, naturally, but the other set. Here, the main query (and thus the aggregations) will be performed on service-center 7, while the secondary will be all service centers.

## Dissecting the response
The following sections are returned:

### Paging
```json
"paging": {
  "page-num": 1,
  "num-per-page": 10,
  "total-items": 11,
  "num-pages": 2,
  "entire-item-count": 31,
  "local-item-count": 11
},
```

### Search reqeust
This is essentially the url's query parameters parsed into json. It is returned to give context to the caller.
```json
"search-request": {
  "search": "copper blue",
  "category-path": "/395",
  "page-num": "1",
  "service-center-id": "7",
  "manufacturer-ids": [
    127,
    199
  ]
},
```

### Documents
```json
"documents": [
  {
    "description": "SEAMLESS ELECTRO TIN PLATED <span class=\"search-term\">COPPER</span> MATERIAL; <span class=\"search-term\">BLUE</span> COLOR; 3.292 INCH OVERALL LENGTH; 1.188 INCH",
    "image-url": "https://images.tradeservice.com/k1m81UL6Q1fAK4zF/PRODUCTIMAGES/DIR100022/IISCOLE00116_1_PE_001.jpg",
    "product-class": "Compression Cable Lug",
    "name": "CSWS40058 1HOLE LUG",
    "id": 38860,
    "upc": "783669644173",
    "manufacturer-name": "Ilsco",
    "matnr": "1388727",
    "summit-part-number": "ILCSWS40058",
    "category-name": "Large Terminals & Connectors",
    "manufacturer-part-number": "CSWS-400-58"
  },
  {
    "description": "SEAMLESS ELECTRO TIN PLATED <span class=\"search-term\">COPPER</span> MATERIAL; <span class=\"search-term\">BLUE</span> COLOR; 3.105 INCH OVERALL LENGTH; 1.188 INCH",
    "image-url": "https://images.tradeservice.com/k1m81UL6Q1fAK4zF/PRODUCTIMAGES/DIR100022/IISCOLE00116_1_PE_001.jpg",
    "product-class": "Compression Cable Lug",
    "name": "CSWS40012 1HOLE LUG",
    ....
```

### Aggregations
We currently return two aggregations: category-path and manufacturer-id.

The category-path will include all child categories of the category-path supplied in the url (that have document hits, of course :o). In this case, all eleven documents are in one category. There are two manufacturers in the Albuquerque service center, 8 sold by Ilsco and 3 by 3M. The category-path-ancestors is not technically an aggregation. However, the UI displays effectively a bread crumb of categories, so were the ancestor paths not provided, the browser would need to make more requests to discern the names to display.

Another note is elasticsearch does not return the __name__. Again, these were added to provide the browser with all necessary information for immediate display.

```json
"aggregations": {
  "category-path": [
    {
      "key": "/395/90",
      "doc_count": 11,
      "name": "Connecting Materials"
    }
  ],
  "manufacturer-id": [
    {
      "key": 127,
      "doc_count": 8,
      "name": "Ilsco"
    },
    {
      "key": 199,
      "doc_count": 3,
      "name": "3M Electrical Products"
    }
  ],
  "category-path-ancestors": [
    {
      "key": "/395",
      "doc_count": 11,
      "name": "Fuses, Batteries, Signaling, Connecting, Fastening & Tools"
    }
  ]
},
```

### Query maps

This section is purely informational. It shows the logical query-maps (main and secondary), and then shows the json queries which were sent to elasticsearch. It is sometimes useful for debugging, but no use for the browser.

```json
"query-maps": {
  "main": {                          ;; logical main query-map
    "search": "copper blue",
    "category-path": "/395",
    "page-num": "1",
    "service-center-id": "7",
    "manufacturer-ids": [
      127,
      199
    ]
  },
  "secondary": {                     ;; logical secondary query-map
    "search": "copper blue",
    "category-path": "/395",
    "page-num": "1",
    "manufacturer-ids": [
      127,
      199
    ],
    "total-items-only": true
  },
  "main-elasticsearch": {            ;; elasticsearch main query
    "from": 0,
    "size": 10,
    "query": {
      "bool": {
        "filter": [
          {
            "terms": {
              "manufacturer-id": [
                127,
                199
              ]
            }
          },
          {
            "term": {
              "service-center-ids": "7"
            }
          },
          {
            "term": {
              "category-ids": "395"
            }
          }
        ],
        "must": [
          {
            "multi_match": {
              "query": "copper blue",
              "type": "cross_fields",
              "operator": "and",
              "fields": [
                "name",
                "description",
                "manufacturer-name",
                "category-name",
                "product-class",
                "upc",
                "manufacturer-part-number",
                "summit-part-number",
                "matnr"
              ]
            }
          }
        ]
      }
    },
    "highlight": {
      "pre_tags": [
        "<span class=\"search-term\">"
      ],
      "post_tags": [
        "</span>"
      ],
      "fields": {
        "*": {}
      }
    },
    "aggregations": {
      "category-path": {
        "terms": {
          "field": "category-path",
          "min_doc_count": 1,
          "size": 4000
        }
      },
      "manufacturer-id": {
        "terms": {
          "field": "manufacturer-id",
          "min_doc_count": 1,
          "size": 100
        }
      }
    }
  },
  "secondary-elasticsearch": {         ;; elasticsearch secondary query
    "from": 0,
    "size": 10,
    "query": {
      "bool": {
        "filter": [
          {
            "terms": {
              "manufacturer-id": [
                127,
                199
              ]
            }
          },
          {
            "term": {
              "category-ids": "395"
            }
          }
        ],
        "must": [
          {
            "multi_match": {
              "query": "copper blue",
              "type": "cross_fields",
              "operator": "and",
              "fields": [
                "name",
                "description",
                "manufacturer-name",
                "category-name",
                "product-class",
                "upc",
                "manufacturer-part-number",
                "summit-part-number",
                "matnr"
              ]
            }
          }
        ]
      }
    },
    "highlight": {
      "pre_tags": [
        "<span class=\"search-term\">"
      ],
      "post_tags": [
        "</span>"
      ],
      "fields": {
        "*": {}
      }
    },
    "aggregations": {
      "category-path": {
        "terms": {
          "field": "category-path",
          "min_doc_count": 1,
          "size": 4000
        }
      },
      "manufacturer-id": {
        "terms": {
        "field": "manufacturer-id",
        "min_doc_count": 1,
        "size": 100
      }
    }
  }
}
```

## Installation

Download from http://example.com/FIXME.

## Usage

    $ clojure repl
    > (routes/restart-server)


## License

Copyright © 2016 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
