/**
 * The service worker caches reads by URL, and a URL says nothing about who asked. On a site
 * phone that is not a hypothetical: one handset is signed into by whoever is on shift, and
 * the roster the morning supervisor pulled would otherwise still be sitting there for the
 * evening one — under a name that has no business seeing it.
 *
 * <p>So the read caches are emptied at both ends of a session. Emptying them costs one
 * refetch to somebody who has just proved they have signal, which is the moment in the day
 * when a refetch costs least.</p>
 *
 * <p>Only the read caches. The precached shell is not touched: it holds no site data, and
 * clearing it would leave a phone that signs out in a valley unable to open the app at
 * all.</p>
 */
const DATA_CACHES = ['reference-data', 'roster'];

export async function clearApiCaches(): Promise<void> {
  if (typeof caches === 'undefined') {
    return;
  }
  try {
    const names = await caches.keys();
    await Promise.all(
      names
        // Workbox prefixes the configured name, so this matches rather than compares.
        .filter((name) => DATA_CACHES.some((cache) => name.includes(cache)))
        .map((name) => caches.delete(name)),
    );
  } catch {
    // A browser that refuses the Cache API still has a working app; it just refetches.
  }
}
