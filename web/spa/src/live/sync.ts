import { api } from '../api/endpoints';
import { useMapStore } from '../state/mapStore';

/** Fetches the seatmap (and sale status) and replaces local state; diffs received meanwhile are re-applied by version. */
export async function syncSeatmap(): Promise<void> {
  const store = useMapStore.getState();
  store.beginLoad();
  try {
    const [map, stats] = await Promise.all([api.seatmap(), api.stats().catch(() => null)]);
    useMapStore.getState().loadSeatmap(map);
    if (stats) useMapStore.getState().setSale(stats.saleStatus, false);
  } catch {
    useMapStore.setState({ loading: false });
  }
}
