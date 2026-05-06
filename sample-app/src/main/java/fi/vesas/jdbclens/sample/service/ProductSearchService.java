package fi.vesas.jdbclens.sample.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import fi.vesas.jdbclens.sample.dao.ProductDao;

/**
 * Demonstrates two query shapes side-by-side: repeated LIKE queries
 * (one per keyword) versus a single IN-clause batch fetch. The profiler
 * sees the LIKE loop as N calls from the same call-site and the batch
 * fetch as a single call — the contrast makes the N+1 pattern visible.
 */
public final class ProductSearchService {

    private final ProductDao products;

    public ProductSearchService(ProductDao products) {
        this.products = products;
    }

    public List<ProductDao.Product> searchByKeyword(Connection c, String keyword) throws SQLException {
        return products.findByNameLike(c, "%" + keyword + "%");
    }

    public List<ProductDao.Product> batchFetchByIds(Connection c, List<Integer> ids) throws SQLException {
        return products.findByIds(c, ids);
    }
}
