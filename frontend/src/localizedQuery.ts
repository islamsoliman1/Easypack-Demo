import { useQuery as baseUseQuery, useQueryClient, hashKey, type QueryKey } from '@tanstack/react-query';
import { getLanguage, useLanguage } from './i18n';

export const localizedKey = (key: QueryKey) => [...key, { language: getLanguage() }];
// Locale is a final key component, so every existing invalidation prefix stays valid.
export const useQuery: typeof baseUseQuery = ((options: any, queryClient?: any) => {
  const language = useLanguage();
  return baseUseQuery({ ...options, queryKey: [...options.queryKey, { language }],
    // API results are normalized to ORIGINAL source values. Keeping the same record's
    // original data during a language-only refetch preserves open forms and cannot
    // show stale translated content: display translation happens in this render.
    placeholderData: (previous: unknown, query: any) => {
      if (query && hashKey(query.queryKey.slice(0, -1)) === hashKey(options.queryKey)) return previous;
      return query?.queryKey.at(-1)?.language === language && options.placeholderData
        ? options.placeholderData(previous, query) : undefined;
    },
  }, queryClient);
}) as typeof baseUseQuery;
export { useQueryClient };
